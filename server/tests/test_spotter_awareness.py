"""Spotter-awareness tests (Phase 7, CLAUDE.md §7, §8, §10).

The training-day bump *math* lives in ``test_targets.py``; here we cover the integration around it:
the :class:`WorkoutSource` abstraction (stubbed — CI never reaches Spotter), the cross-app token, the
best-effort ``is_training_day`` helper, and the visible end-to-end effect on ``GET /goals/targets``,
``GET /log``, and the coach's macro context.
"""
import datetime

import httpx
import pytest
import pytest_asyncio
from jose import jwt

from app.config import settings
from app.database import AsyncSessionLocal
from app.main import app
from app.models.user_goal import UserGoal
from app.services.ai.client import chat
from app.services.ai.context_service import build_macro_context
from app.services.cross_app_token import (
    cross_app_configured,
    fetch_cross_app_token,
    mint_legacy_cross_app_token as mint_cross_app_token,
)
from app.services.workout_source import (
    NOT_TRAINED,
    NullWorkoutSource,
    SpotterWorkoutSource,
    WorkoutSource,
    WorkoutStatus,
    get_workout_source,
    is_training_day,
)
from tests.test_ai import _make_user, _mock_client, _req

TODAY = datetime.date.today()


class StubWorkoutSource(WorkoutSource):
    """Deterministic source for tests — no network, configurable trained flag."""

    def __init__(self, trained: bool, *, raises: bool = False) -> None:
        self._trained = trained
        self._raises = raises

    async def trained_on(self, email: str, day: datetime.date) -> WorkoutStatus:
        if self._raises:
            raise httpx.ConnectError("spotter unreachable")
        return WorkoutStatus(trained=self._trained, strength_sessions=1 if self._trained else 0)


@pytest.fixture
def cross_app_secret():
    """Set a shared secret for the test and restore the prior value afterward."""
    prior = settings.cross_app_secret
    settings.cross_app_secret = "test-shared-secret"
    yield settings.cross_app_secret
    settings.cross_app_secret = prior


@pytest.fixture
def override_workouts():
    """Override the workout-source dependency with a stub; clean up after the test."""

    def _apply(source: WorkoutSource):
        app.dependency_overrides[get_workout_source] = lambda: source

    yield _apply
    app.dependency_overrides.pop(get_workout_source, None)


# ── Sources ──────────────────────────────────────────────────────────────────


async def test_null_source_never_trained():
    assert await NullWorkoutSource().trained_on("a@b.com", TODAY) == NOT_TRAINED


async def test_spotter_source_parses_response(cross_app_secret):
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("Authorization")
        return httpx.Response(
            200,
            json={"date": TODAY.isoformat(), "trained": True, "strength_sessions": 2,
                  "cardio_sessions": 1},
        )

    async with _mock_client(handler) as client:
        source = SpotterWorkoutSource("https://spotter.example/", client=client)
        status = await source.trained_on("lifter@plate.com", TODAY)

    assert status == WorkoutStatus(trained=True, strength_sessions=2, cardio_sessions=1)
    assert f"date={TODAY.isoformat()}" in captured["url"]
    assert captured["auth"].startswith("Bearer ")


async def test_spotter_source_raises_on_http_error(cross_app_secret):
    async with _mock_client(lambda r: httpx.Response(500)) as client:
        source = SpotterWorkoutSource("https://spotter.example", client=client)
        with pytest.raises(httpx.HTTPStatusError):
            await source.trained_on("lifter@plate.com", TODAY)


# ── Cross-app token ────────────────────────────────────────────────────────────


def test_mint_cross_app_token_carries_email_and_type(cross_app_secret):
    token = mint_cross_app_token("lifter@plate.com")
    payload = jwt.decode(token, cross_app_secret, algorithms=[settings.algorithm])
    assert payload["email"] == "lifter@plate.com"
    assert payload["type"] == "cross_app"


def test_mint_cross_app_token_requires_secret():
    prior = settings.cross_app_secret
    settings.cross_app_secret = None
    try:
        with pytest.raises(RuntimeError):
            mint_cross_app_token("lifter@plate.com")
    finally:
        settings.cross_app_secret = prior


@pytest.fixture
def rs256_client_creds(monkeypatch):
    """Configure Plate as a dragonfly-id confidential client (ROADMAP T2 #5 RS256 path)."""
    monkeypatch.setattr(settings, "cross_app_client_id", "plate")
    monkeypatch.setattr(settings, "cross_app_client_secret", "s3cret")
    monkeypatch.setattr(settings, "suite_issuer", "https://id.test")


async def test_fetch_token_uses_dragonfly_id_when_client_configured(rs256_client_creds):
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = request.read().decode()
        return httpx.Response(200, json={"access_token": "rs256-abc", "expires_in": 120})

    async with _mock_client(handler) as client:
        token = await fetch_cross_app_token("lifter@plate.com", client=client)

    assert token == "rs256-abc"
    assert captured["url"].endswith("/cross-app/token")
    assert "client_id=plate" in captured["body"]
    assert "subject_email=lifter" in captured["body"]  # url-encoded email present


async def test_fetch_token_falls_back_to_hs256_without_client_creds(cross_app_secret):
    # No client creds → the legacy HS256 path (no network), decodable with the shared secret.
    token = await fetch_cross_app_token("lifter@plate.com")
    payload = jwt.decode(token, cross_app_secret, algorithms=[settings.algorithm])
    assert payload["email"] == "lifter@plate.com" and payload["type"] == "cross_app"


def test_cross_app_configured_true_with_secret(cross_app_secret):
    assert cross_app_configured() is True


# ── is_training_day (best-effort) ───────────────────────────────────────────────


async def test_is_training_day_true_from_source():
    assert await is_training_day("a@b.com", TODAY, source=StubWorkoutSource(True)) is True


async def test_is_training_day_false_from_source():
    assert await is_training_day("a@b.com", TODAY, source=StubWorkoutSource(False)) is False


async def test_is_training_day_degrades_to_false_on_error():
    # A Spotter outage must never break the caller — it just means no bump.
    assert await is_training_day("a@b.com", TODAY, source=StubWorkoutSource(True, raises=True)) is False


async def test_is_training_day_uses_null_source_when_unconfigured():
    # No spotter_base_url/secret → factory yields NullWorkoutSource → never trained.
    assert await is_training_day("a@b.com", TODAY) is False


# ── Factory ──────────────────────────────────────────────────────────────────


def test_factory_null_when_unconfigured():
    assert isinstance(get_workout_source(), NullWorkoutSource)


def test_factory_spotter_when_configured(cross_app_secret):
    prior_url = settings.spotter_base_url
    settings.spotter_base_url = "https://spotter.example"
    try:
        assert isinstance(get_workout_source(), SpotterWorkoutSource)
    finally:
        settings.spotter_base_url = prior_url


# ── Coach framing ──────────────────────────────────────────────────────────────


async def test_macro_context_mentions_training_when_trained():
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        ctx = await build_macro_context(session, user.id, TODAY, trained=True)
    assert ctx is not None
    assert "trained today" in ctx.lower()


async def test_chat_passes_training_framing_to_model():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["payload"] = request.read()
        return httpx.Response(
            200, json={"choices": [{"message": {"role": "assistant", "content": "Refuel!"}}]}
        )

    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        async with _mock_client(handler) as client:
            await chat(_req("what should I eat?"), session, user.id, trained=True, client=client)
    assert b"trained today" in captured["payload"].lower()


# ── End-to-end via the API (stubbed source) ──────────────────────────────────


async def _set_goal(auth_client):
    resp = await auth_client.put(
        "/goals",
        json={
            "goal_type": "cut",
            "weight_kg": 80.0,
            "height_cm": 180.0,
            "age": 30,
            "sex": "male",
            "activity_level": "moderate",
            "rate_kg_per_week": -0.5,
        },
    )
    assert resp.status_code == 200, resp.text


async def test_targets_endpoint_bumps_on_training_day(auth_client, override_workouts):
    await _set_goal(auth_client)

    override_workouts(StubWorkoutSource(False))
    base = (await auth_client.get("/goals/targets")).json()
    assert base["trained_today"] is False

    override_workouts(StubWorkoutSource(True))
    bumped = (await auth_client.get("/goals/targets")).json()
    assert bumped["trained_today"] is True
    assert bumped["kcal"] > base["kcal"]
    assert bumped["carbs_g"] > base["carbs_g"]
    assert bumped["protein_g"] > base["protein_g"]


async def test_log_endpoint_reports_trained_today(auth_client, override_workouts):
    await _set_goal(auth_client)
    override_workouts(StubWorkoutSource(True))
    body = (await auth_client.get("/log")).json()
    assert body["trained_today"] is True


async def test_log_endpoint_not_trained_by_default(auth_client, override_workouts):
    await _set_goal(auth_client)
    override_workouts(StubWorkoutSource(False))
    body = (await auth_client.get("/log")).json()
    assert body["trained_today"] is False

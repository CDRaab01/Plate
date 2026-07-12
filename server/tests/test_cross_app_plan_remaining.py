"""Federated awareness Links E (plan → coach context) and F (remaining-macros provider)."""

import datetime

import httpx
import pytest

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.food_log_entry import FoodLogEntry
from app.models.user_goal import UserGoal
from app.services.plan_source import (
    CookbookPlanSource,
    NullPlanSource,
    PlannedMeal,
    planned_meals,
)
from tests.test_ai import _make_user, _mock_client
from tests.test_cross_app_foods import SECRET, _mint, _register

TODAY = datetime.date.today()


# ── Link E: plan source ──────────────────────────────────────────────────────


class StubPlanSource(NullPlanSource):
    def __init__(self, meals, *, raises=False):
        self._meals = meals
        self._raises = raises

    async def planned_on(self, email, day):
        if self._raises:
            raise httpx.ConnectError("cookbook unreachable")
        return self._meals


@pytest.fixture
def cross_app_secret():
    # Match the secret _mint signs with, so the legacy HS256 dual-accept path verifies.
    prior = settings.cross_app_secret
    settings.cross_app_secret = SECRET
    yield
    settings.cross_app_secret = prior


async def test_null_plan_source_is_empty():
    assert await NullPlanSource().planned_on("a@b.com", TODAY) == []


async def test_cookbook_plan_source_parses_and_drops_noteless(cross_app_secret):
    captured = {}

    def handler(request):
        captured["url"] = str(request.url)
        return httpx.Response(200, json={
            "date": TODAY.isoformat(),
            "entries": [
                {"slot": "dinner", "recipe_name": "Chicken Tikka"},
                {"slot": "lunch", "recipe_name": None},  # dropped
            ],
        })

    async with _mock_client(handler) as client:
        source = CookbookPlanSource("https://cookbook.example/", client=client)
        meals = await source.planned_on("eater@plate.com", TODAY)

    assert meals == [PlannedMeal(slot="dinner", name="Chicken Tikka")]
    assert f"date={TODAY.isoformat()}" in captured["url"]


async def test_planned_meals_degrades_to_empty_on_failure():
    meals = await planned_meals("a@b.com", TODAY, source=StubPlanSource([], raises=True))
    assert meals == []


async def test_macro_context_includes_plan_line():
    from app.services.ai.context_service import build_macro_context

    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        context = await build_macro_context(
            session, user.id, TODAY,
            plan=[PlannedMeal(slot="dinner", name="Chicken Tikka")],
        )
    assert context is not None
    assert "Planned meals today (reported by Cookbook)" in context
    assert "Chicken Tikka (dinner)" in context


# ── Link F: remaining-macros provider ────────────────────────────────────────


async def _user_with_goal(client) -> str:
    email = await _register(client)
    async with AsyncSessionLocal() as session:
        from sqlalchemy import select
        from app.models.user import User

        user = (await session.execute(select(User).where(User.email == email))).scalar_one()
        session.add(
            UserGoal(
                user_id=user.id, goal_type="cut", weight_kg=80.0, height_cm=180.0,
                age=30, sex="male", activity_level="moderate", rate_kg_per_week=-0.5,
            )
        )
        await session.commit()
    return email


async def test_remaining_requires_cross_app_token(client, cross_app_secret):
    resp = await client.get("/cross-app/remaining", params={"date": TODAY.isoformat()})
    assert resp.status_code == 401


async def test_remaining_404_without_goal(client, cross_app_secret):
    email = await _register(client)  # registered but no goal
    resp = await client.get(
        "/cross-app/remaining", params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {_mint(email)}"},
    )
    assert resp.status_code == 404


async def test_remaining_returns_positive_macros_with_goal(client, cross_app_secret):
    email = await _user_with_goal(client)
    resp = await client.get(
        "/cross-app/remaining", params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {_mint(email)}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body.keys()) == {
        "date", "kcal_remaining", "protein_g_remaining", "carbs_g_remaining", "fat_g_remaining"
    }
    # Nothing logged yet → remaining == full targets, all positive.
    assert body["kcal_remaining"] > 0 and body["protein_g_remaining"] > 0

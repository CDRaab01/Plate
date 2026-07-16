"""Cross-app weekly summary provider: GET /cross-app/summary (suite weekly digest, Link G).

Same trust model as /cross-app/remaining — an RS256/HS256 cross-app token carrying the user's email,
disabled (401) when the secret/JWKS is unset. Aggregates only.
"""

import datetime
import uuid

import pytest

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.body_metric import BodyMetric
from app.models.food_log_entry import FoodLogEntry
from app.models.user import User
from app.models.user_goal import UserGoal
from tests.test_cross_app_foods import SECRET, _mint, _register

TODAY = datetime.date.today()
# A fixed 7-day window well clear of "today" so adaptive-TDEE history never engages.
START = TODAY - datetime.timedelta(days=40)
END = START + datetime.timedelta(days=6)


@pytest.fixture(autouse=True)
def cross_app_secret():
    old = settings.cross_app_secret
    settings.cross_app_secret = SECRET
    yield
    settings.cross_app_secret = old


async def _user_id(email: str) -> uuid.UUID:
    async with AsyncSessionLocal() as session:
        from sqlalchemy import select

        user = (await session.execute(select(User).where(User.email == email))).scalar_one()
        return user.id


async def _add_goal(user_id: uuid.UUID) -> None:
    async with AsyncSessionLocal() as session:
        session.add(
            UserGoal(
                user_id=user_id,
                goal_type="cut",
                weight_kg=80.0,
                height_cm=180.0,
                age=30,
                sex="male",
                activity_level="moderate",
                rate_kg_per_week=-0.5,
            )
        )
        await session.commit()


async def _log_day(user_id: uuid.UUID, day: datetime.date, kcal: float, protein_g: float) -> None:
    async with AsyncSessionLocal() as session:
        session.add(
            FoodLogEntry(
                user_id=user_id,
                food_id=None,
                name="test entry",
                date=day,
                meal="dinner",
                quantity=1.0,
                unit="serving",
                kcal=kcal,
                protein_g=protein_g,
                carbs_g=0.0,
                fat_g=0.0,
            )
        )
        await session.commit()


async def _add_weighin(user_id: uuid.UUID, day: datetime.date, weight_kg: float) -> None:
    async with AsyncSessionLocal() as session:
        session.add(BodyMetric(user_id=user_id, date=day, weight=weight_kg))
        await session.commit()


def _params(start: datetime.date = START, end: datetime.date = END) -> dict:
    return {"start": start.isoformat(), "end": end.isoformat()}


async def test_summary_requires_cross_app_token(client):
    resp = await client.get("/cross-app/summary", params=_params())
    assert resp.status_code == 401


async def test_summary_rejects_normal_tokens(auth_client):
    resp = await auth_client.get("/cross-app/summary", params=_params())
    assert resp.status_code == 401


async def test_summary_empty_window_zeros_and_nulls(client):
    email = await _register(client)
    await _add_goal(await _user_id(email))
    resp = await client.get(
        "/cross-app/summary",
        params=_params(),
        headers={"Authorization": f"Bearer {_mint(email)}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body.keys()) == {
        "start",
        "end",
        "days_in_window",
        "days_logged",
        "avg_calories",
        "calorie_adherence_pct",
        "protein_adherence_pct",
        "weight_change_kg",
    }
    assert body["days_in_window"] == 7
    assert body["days_logged"] == 0
    assert body["avg_calories"] == 0.0
    assert body["calorie_adherence_pct"] is None
    assert body["protein_adherence_pct"] is None
    assert body["weight_change_kg"] is None


async def test_summary_counts_logged_days_and_adherence(client):
    email = await _register(client)
    uid = await _user_id(email)
    await _add_goal(uid)
    # Goal (cut, 80 kg, moderate) → target ≈ 2209 kcal / 160 g protein; band ±10% ≈ [1988, 2430].
    await _log_day(uid, START, 2200.0, 165.0)  # cal ✓  protein ✓
    await _log_day(uid, START + datetime.timedelta(days=2), 500.0, 50.0)  # cal ✗  protein ✗
    await _log_day(uid, START + datetime.timedelta(days=4), 2250.0, 100.0)  # cal ✓  protein ✗
    # Two weigh-ins in the window → net change latest − earliest.
    await _add_weighin(uid, START, 80.0)
    await _add_weighin(uid, END, 79.0)

    resp = await client.get(
        "/cross-app/summary",
        params=_params(),
        headers={"Authorization": f"Bearer {_mint(email)}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["days_in_window"] == 7
    assert body["days_logged"] == 3
    assert body["avg_calories"] == pytest.approx(1650.0)  # (2200+500+2250)/3
    assert body["calorie_adherence_pct"] == pytest.approx(66.7)  # 2 of 3
    assert body["protein_adherence_pct"] == pytest.approx(33.3)  # 1 of 3
    assert body["weight_change_kg"] == pytest.approx(-1.0)


async def test_summary_without_goal_has_null_adherence_but_counts_days(client):
    email = await _register(client)  # no goal set
    uid = await _user_id(email)
    await _log_day(uid, START, 1800.0, 120.0)
    await _log_day(uid, START + datetime.timedelta(days=1), 2000.0, 130.0)

    resp = await client.get(
        "/cross-app/summary",
        params=_params(),
        headers={"Authorization": f"Bearer {_mint(email)}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["days_logged"] == 2
    assert body["avg_calories"] == pytest.approx(1900.0)
    assert body["calorie_adherence_pct"] is None  # no goal → nothing to measure against
    assert body["protein_adherence_pct"] is None
    assert body["weight_change_kg"] is None  # <2 weigh-ins


async def test_summary_rejects_reversed_and_oversized_window(client):
    email = await _register(client)
    headers = {"Authorization": f"Bearer {_mint(email)}"}

    reversed_resp = await client.get(
        "/cross-app/summary", params=_params(start=END, end=START), headers=headers
    )
    assert reversed_resp.status_code == 400

    big = await client.get(
        "/cross-app/summary",
        params=_params(start=START, end=START + datetime.timedelta(days=92)),
        headers=headers,
    )
    assert big.status_code == 400

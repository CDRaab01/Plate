"""POST /log/batch — the food-search multi-select add (several foods in one call)."""

import uuid

from app.database import AsyncSessionLocal
from app.models.food import Food

DAY = "2026-07-20"


async def _food(name: str) -> Food:
    async with AsyncSessionLocal() as session:
        f = Food(
            source="user",
            name=f"{name} {uuid.uuid4().hex[:6]}",
            kcal_per_100g=100.0,
            protein_g_per_100g=10.0,
            carbs_g_per_100g=5.0,
            fat_g_per_100g=2.0,
            serving_size=50.0,
            serving_unit="g",
        )
        session.add(f)
        await session.commit()
        await session.refresh(f)
        return f


async def test_batch_logs_several_foods_at_once(auth_client):
    a = await _food("Chicken")
    b = await _food("Rice")
    resp = await auth_client.post(
        "/log/batch",
        json={
            "entries": [
                {
                    "food_id": str(a.id),
                    "date": DAY,
                    "meal": "lunch",
                    "quantity": 100.0,
                    "unit": "g",
                },
                {
                    "food_id": str(b.id),
                    "date": DAY,
                    "meal": "lunch",
                    "quantity": 200.0,
                    "unit": "g",
                },
            ]
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert len(body) == 2
    assert {e["food_name"] for e in body} == {a.name, b.name}

    # Both landed in the same day/meal.
    day = (await auth_client.get("/log", params={"date": DAY})).json()
    lunch = next(m for m in day["meals"] if m["meal"] == "lunch")
    logged_ids = {e["food_id"] for e in lunch["entries"]}
    assert str(a.id) in logged_ids and str(b.id) in logged_ids


async def test_batch_is_all_or_nothing_on_unknown_food(auth_client):
    a = await _food("Beans")
    missing = str(uuid.uuid4())
    resp = await auth_client.post(
        "/log/batch",
        json={
            "entries": [
                {
                    "food_id": str(a.id),
                    "date": DAY,
                    "meal": "dinner",
                    "quantity": 100.0,
                    "unit": "g",
                },
                {"food_id": missing, "date": DAY, "meal": "dinner", "quantity": 1.0, "unit": "g"},
            ]
        },
    )
    assert resp.status_code == 404
    # Nothing was committed — the good entry must not have leaked in.
    day = (await auth_client.get("/log", params={"date": DAY})).json()
    dinner = next((m for m in day["meals"] if m["meal"] == "dinner"), None)
    dinner_ids = {e["food_id"] for e in dinner["entries"]} if dinner else set()
    assert str(a.id) not in dinner_ids


async def test_batch_rejects_empty_list(auth_client):
    resp = await auth_client.post("/log/batch", json={"entries": []})
    assert resp.status_code == 422


async def test_batch_requires_auth(client):
    resp = await client.post("/log/batch", json={"entries": []})
    assert resp.status_code == 401

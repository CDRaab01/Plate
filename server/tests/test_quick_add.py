"""API tests for quick-add: raw macros logged with no source food (Phase 8)."""
import datetime


async def test_quick_add_creates_foodless_entry(auth_client):
    resp = await auth_client.post(
        "/log/quick-add",
        json={
            "date": "2026-06-16",
            "meal": "snack",
            "name": "Protein shake",
            "kcal": 200.0,
            "protein_g": 30.0,
            "carbs_g": 10.0,
            "fat_g": 3.0,
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["food_id"] is None
    assert body["food_name"] == "Protein shake"
    assert body["kcal"] == 200.0
    assert body["meal"] == "snack"


async def test_quick_add_defaults_label(auth_client):
    resp = await auth_client.post(
        "/log/quick-add",
        json={"date": "2026-06-16", "meal": "lunch", "kcal": 100.0,
              "protein_g": 0.0, "carbs_g": 25.0, "fat_g": 0.0},
    )
    assert resp.status_code == 201
    assert resp.json()["food_name"] == "Quick add"


async def test_quick_add_appears_in_day_totals(auth_client):
    day = "2026-09-09"
    await auth_client.post(
        "/log/quick-add",
        json={"date": day, "meal": "breakfast", "name": "Coffee + milk",
              "kcal": 50.0, "protein_g": 2.0, "carbs_g": 5.0, "fat_g": 2.0},
    )
    resp = await auth_client.get("/log", params={"date": day})
    by_meal = {m["meal"]: m for m in resp.json()["meals"]}
    assert by_meal["breakfast"]["totals"]["kcal"] == 50.0
    assert resp.json()["totals"]["protein_g"] == 2.0


async def test_quick_add_rejects_negative(auth_client):
    resp = await auth_client.post(
        "/log/quick-add",
        json={"date": "2026-06-16", "meal": "snack", "kcal": -5.0,
              "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 0.0},
    )
    assert resp.status_code == 422


async def test_quick_add_rejects_bad_meal(auth_client):
    resp = await auth_client.post(
        "/log/quick-add",
        json={"date": "2026-06-16", "meal": "brunch", "kcal": 5.0,
              "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 0.0},
    )
    assert resp.status_code == 422


async def test_quick_add_requires_auth(client):
    resp = await client.post(
        "/log/quick-add",
        json={"date": datetime.date.today().isoformat(), "meal": "snack",
              "kcal": 5.0, "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 0.0},
    )
    assert resp.status_code == 401

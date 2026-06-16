"""API integration tests for food search + manual logging (CLAUDE.md §6, §10).

Exercises the full create / read / update / delete log flow, meal bucketing, daily totals, the
denormalized snapshot, and ownership isolation — all against the test DB with external sources
disabled (``settings.food_search_live = False`` in conftest).
"""
import datetime
import uuid

DAY = "2026-06-16"


async def _register(client) -> str:
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Logger", "email": f"log_{uid}@plate.com", "password": "Testpass123!"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


# ── Search & custom food ─────────────────────────────────────────────────────


async def test_search_requires_auth(client):
    resp = await client.get("/foods/search", params={"q": "banana"})
    assert resp.status_code == 401


async def test_search_finds_local_food(auth_client, food):
    resp = await auth_client.get("/foods/search", params={"q": "Test Banana"})
    assert resp.status_code == 200
    names = [f["name"] for f in resp.json()]
    assert food.name in names


async def test_search_rejects_blank_query(auth_client):
    resp = await auth_client.get("/foods/search", params={"q": ""})
    assert resp.status_code == 422


async def test_create_custom_food(auth_client):
    resp = await auth_client.post(
        "/foods",
        json={
            "name": "Homemade Granola",
            "kcal_per_100g": 450.0,
            "protein_g_per_100g": 10.0,
            "carbs_g_per_100g": 60.0,
            "fat_g_per_100g": 18.0,
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["source"] == "user"
    assert body["name"] == "Homemade Granola"


async def test_create_custom_food_rejects_negative_macros(auth_client):
    resp = await auth_client.post(
        "/foods",
        json={
            "name": "Bad",
            "kcal_per_100g": -1.0,
            "protein_g_per_100g": 0.0,
            "carbs_g_per_100g": 0.0,
            "fat_g_per_100g": 0.0,
        },
    )
    assert resp.status_code == 422


# ── Logging ──────────────────────────────────────────────────────────────────


async def test_create_log_entry_snapshots_macros(auth_client, food):
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),
            "date": DAY,
            "meal": "breakfast",
            "quantity": 100.0,
            "unit": "g",
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["meal"] == "breakfast"
    assert body["kcal"] == 89.0  # 100g of an 89 kcal/100g food
    assert body["food_name"] == food.name


async def test_create_log_entry_unknown_food_404(auth_client):
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(uuid.uuid4()),
            "date": DAY,
            "meal": "lunch",
            "quantity": 1.0,
            "unit": "g",
        },
    )
    assert resp.status_code == 404


async def test_create_log_entry_invalid_meal_422(auth_client, food):
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),
            "date": DAY,
            "meal": "brunch",
            "quantity": 100.0,
            "unit": "g",
        },
    )
    assert resp.status_code == 422


async def test_day_groups_by_meal_with_totals_and_targets(auth_client, food):
    day = "2026-07-01"
    for meal, qty in [("breakfast", 100.0), ("lunch", 200.0), ("dinner", 50.0)]:
        resp = await auth_client.post(
            "/log",
            json={"food_id": str(food.id), "date": day, "meal": meal, "quantity": qty, "unit": "g"},
        )
        assert resp.status_code == 201

    resp = await auth_client.get("/log", params={"date": day})
    assert resp.status_code == 200
    data = resp.json()
    assert data["date"] == day
    assert [m["meal"] for m in data["meals"]] == ["breakfast", "lunch", "dinner", "snack"]

    by_meal = {m["meal"]: m for m in data["meals"]}
    assert by_meal["breakfast"]["totals"]["kcal"] == 89.0
    assert by_meal["lunch"]["totals"]["kcal"] == 178.0
    assert by_meal["snack"]["entries"] == []

    # Day total = sum of all meals (350g × 0.89 kcal/g).
    assert data["totals"]["kcal"] == 89.0 + 178.0 + 44.5
    # Static Phase-2 placeholder targets are surfaced.
    assert data["targets"]["kcal"] == 2000.0


async def test_update_entry_resnapshots_macros(auth_client, food):
    create = await auth_client.post(
        "/log",
        json={"food_id": str(food.id), "date": DAY, "meal": "snack", "quantity": 100.0, "unit": "g"},
    )
    entry_id = create.json()["id"]

    resp = await auth_client.put(f"/log/{entry_id}", json={"quantity": 200.0})
    assert resp.status_code == 200
    body = resp.json()
    assert body["quantity"] == 200.0
    assert body["kcal"] == 178.0


async def test_update_entry_can_move_meal(auth_client, food):
    create = await auth_client.post(
        "/log",
        json={"food_id": str(food.id), "date": DAY, "meal": "breakfast", "quantity": 100.0, "unit": "g"},
    )
    entry_id = create.json()["id"]
    resp = await auth_client.put(f"/log/{entry_id}", json={"meal": "dinner"})
    assert resp.status_code == 200
    assert resp.json()["meal"] == "dinner"
    assert resp.json()["kcal"] == 89.0  # unchanged when only the meal moves


async def test_delete_entry(auth_client, food):
    create = await auth_client.post(
        "/log",
        json={"food_id": str(food.id), "date": DAY, "meal": "lunch", "quantity": 100.0, "unit": "g"},
    )
    entry_id = create.json()["id"]
    resp = await auth_client.delete(f"/log/{entry_id}")
    assert resp.status_code == 204

    day = await auth_client.get("/log", params={"date": DAY})
    ids = [e["id"] for m in day.json()["meals"] for e in m["entries"]]
    assert entry_id not in ids


async def test_cannot_edit_another_users_entry(auth_client, client, food):
    create = await auth_client.post(
        "/log",
        json={"food_id": str(food.id), "date": DAY, "meal": "lunch", "quantity": 100.0, "unit": "g"},
    )
    entry_id = create.json()["id"]

    other_token = await _register(client)
    client.headers["Authorization"] = f"Bearer {other_token}"
    resp = await client.put(f"/log/{entry_id}", json={"quantity": 999.0})
    assert resp.status_code == 404
    resp = await client.delete(f"/log/{entry_id}")
    assert resp.status_code == 404


async def test_day_defaults_to_today(auth_client):
    resp = await auth_client.get("/log")
    assert resp.status_code == 200
    assert resp.json()["date"] == datetime.date.today().isoformat()

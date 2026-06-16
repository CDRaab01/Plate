"""API tests for saved meals / recipes (CLAUDE.md §4, Phase 8).

Covers CRUD, read-time totals from the source foods, expanding a recipe into the day's log
(snapshotted history), and per-user ownership isolation. External sources stay disabled (conftest).
"""
import uuid

DAY = "2026-06-16"


async def _register(client) -> str:
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Cook", "email": f"cook_{uid}@plate.com", "password": "Testpass123!"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _make_recipe(auth_client, recipe_food, name="Banana Bowl", qty=200.0):
    resp = await auth_client.post(
        "/recipes",
        json={
            "name": name,
            "description": "Two servings of banana",
            "items": [{"food_id": str(recipe_food.id), "quantity": qty, "unit": "g"}],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_create_recipe_computes_totals(auth_client, recipe_food):
    body = await _make_recipe(auth_client, recipe_food, qty=200.0)
    assert body["name"] == "Banana Bowl"
    assert len(body["items"]) == 1
    # 200g of an 89 kcal/100g recipe_food.
    assert body["totals"]["kcal"] == 178.0
    assert body["items"][0]["kcal"] == 178.0
    assert body["items"][0]["food_name"] == recipe_food.name
    assert body["items"][0]["order"] == 0


async def test_recipe_requires_auth(client):
    resp = await client.get("/recipes")
    assert resp.status_code == 401


async def test_list_and_get_recipe(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food)
    lst = await auth_client.get("/recipes")
    assert lst.status_code == 200
    assert created["id"] in [r["id"] for r in lst.json()]

    one = await auth_client.get(f"/recipes/{created['id']}")
    assert one.status_code == 200
    assert one.json()["id"] == created["id"]


async def test_update_recipe_name(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food)
    resp = await auth_client.patch(f"/recipes/{created['id']}", json={"name": "Renamed"})
    assert resp.status_code == 200
    assert resp.json()["name"] == "Renamed"


async def test_replace_items(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food, qty=100.0)
    resp = await auth_client.put(
        f"/recipes/{created['id']}/items",
        json={"items": [{"food_id": str(recipe_food.id), "quantity": 300.0, "unit": "g"}]},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 1
    assert body["items"][0]["quantity"] == 300.0
    assert body["totals"]["kcal"] == 267.0  # 300g × 0.89


async def test_log_recipe_expands_into_entries(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food, qty=200.0)
    day = "2026-08-01"
    resp = await auth_client.post(
        f"/recipes/{created['id']}/log", json={"date": day, "meal": "dinner"}
    )
    assert resp.status_code == 201, resp.text
    entries = resp.json()
    assert len(entries) == 1
    assert entries[0]["kcal"] == 178.0
    assert entries[0]["meal"] == "dinner"

    # The logged entry shows up in the day's dinner bucket.
    day_resp = await auth_client.get("/log", params={"date": day})
    by_meal = {m["meal"]: m for m in day_resp.json()["meals"]}
    assert by_meal["dinner"]["totals"]["kcal"] == 178.0


async def test_log_recipe_invalid_meal_422(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food)
    resp = await auth_client.post(
        f"/recipes/{created['id']}/log", json={"date": DAY, "meal": "brunch"}
    )
    assert resp.status_code == 422


async def test_delete_recipe(auth_client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food)
    resp = await auth_client.delete(f"/recipes/{created['id']}")
    assert resp.status_code == 204
    gone = await auth_client.get(f"/recipes/{created['id']}")
    assert gone.status_code == 404


async def test_cannot_access_another_users_recipe(auth_client, client, recipe_food):
    created = await _make_recipe(auth_client, recipe_food)
    other = await _register(client)
    client.headers["Authorization"] = f"Bearer {other}"
    assert (await client.get(f"/recipes/{created['id']}")).status_code == 404
    assert (
        await client.post(f"/recipes/{created['id']}/log", json={"date": DAY, "meal": "lunch"})
    ).status_code == 404
    assert (await client.delete(f"/recipes/{created['id']}")).status_code == 404

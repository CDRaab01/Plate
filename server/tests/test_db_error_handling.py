"""Regression tests for database errors that used to surface as 500s.

Found by property-based (Schemathesis) fuzzing: a value Postgres can't store (a NUL byte
in text) or a request referencing a non-existent foreign key tripped a DB error that
wasn't caught and returned 500. They must now return clean 4xx.
"""
import uuid


async def _create_recipe(auth_client, name: str = "Reg Test") -> dict:
    resp = await auth_client.post("/recipes", json={"name": name, "items": []})
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_create_recipe_with_nul_byte_returns_422(auth_client):
    """A NUL byte in text is rejected by Postgres (SQLSTATE 22021) -> 422, not 500."""
    resp = await auth_client.post("/recipes", json={"name": "bad\x00name", "items": []})
    assert resp.status_code == 422, resp.text


async def test_recipe_items_with_unknown_food_returns_conflict(auth_client):
    """Replacing recipe items with an unknown food_id hits the FK constraint -> 409, not 500."""
    recipe = await _create_recipe(auth_client)
    resp = await auth_client.put(
        f"/recipes/{recipe['id']}/items",
        json={"items": [{"food_id": str(uuid.uuid4()), "quantity": 100.0, "unit": "g"}]},
    )
    assert resp.status_code == 409, resp.text

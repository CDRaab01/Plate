"""User settings (unit_system) API tests (CLAUDE.md §10)."""


async def test_default_unit_system_is_imperial(auth_client):
    resp = await auth_client.get("/users/me")
    assert resp.status_code == 200
    assert resp.json()["unit_system"] == "imperial"


async def test_patch_unit_system_flips_and_persists(auth_client):
    resp = await auth_client.patch("/users/me/settings", json={"unit_system": "metric"})
    assert resp.status_code == 200
    assert resp.json()["unit_system"] == "metric"
    # Persisted across a fresh read.
    assert (await auth_client.get("/users/me")).json()["unit_system"] == "metric"


async def test_patch_unit_system_rejects_invalid(auth_client):
    resp = await auth_client.patch("/users/me/settings", json={"unit_system": "stones"})
    assert resp.status_code == 422


async def test_settings_requires_auth(client):
    resp = await client.patch("/users/me/settings", json={"unit_system": "metric"})
    assert resp.status_code == 401

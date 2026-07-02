"""Cross-app recipe export for Cookbook's migration (GET /recipes/export).

Mirrors test_spotter_awareness's approach to cross-app tokens, but from the validating side:
Plate accepts a JWT signed with CROSS_APP_SECRET, typed ``cross_app``, carrying the user's email.
"""

from datetime import datetime, timedelta, timezone

import pytest
from jose import jwt

from app.config import settings

SECRET = "test-cross-app-secret"


def _mint(email: str, *, secret: str = SECRET, token_type: str = "cross_app") -> str:
    return jwt.encode(
        {
            "email": email,
            "type": token_type,
            "exp": datetime.now(timezone.utc) + timedelta(seconds=60),
        },
        secret,
        algorithm="HS256",
    )


@pytest.fixture(autouse=True)
def cross_app_secret():
    old = settings.cross_app_secret
    settings.cross_app_secret = SECRET
    yield
    settings.cross_app_secret = old


async def _register_with_recipe(client, food):
    import uuid as _uuid

    uid = _uuid.uuid4().hex[:8]
    email = f"export_{uid}@plate.com"
    resp = await client.post(
        "/auth/register",
        json={"name": "Exporter", "email": email, "password": "Testpass123!"},
    )
    token = resp.json()["access_token"]
    resp = await client.post(
        "/recipes",
        json={
            "name": "Banana Bowl",
            "description": "Test recipe",
            "items": [{"food_id": str(food.id), "quantity": 118, "unit": "g"}],
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 201, resp.text
    return email


async def test_export_returns_user_recipes(client, food):
    email = await _register_with_recipe(client, food)

    resp = await client.get(
        "/recipes/export", headers={"Authorization": f"Bearer {_mint(email)}"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body) == 1
    assert body[0]["name"] == "Banana Bowl"
    assert body[0]["items"][0]["food_name"] == food.name
    assert body[0]["items"][0]["quantity"] == 118
    assert body[0]["items"][0]["unit"] == "g"


async def test_export_rejects_plate_user_token(client, food):
    """A normal Plate access token must not reach the cross-app surface."""
    import uuid as _uuid

    uid = _uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "N", "email": f"n_{uid}@plate.com", "password": "Testpass123!"},
    )
    access = resp.json()["access_token"]
    resp = await client.get("/recipes/export", headers={"Authorization": f"Bearer {access}"})
    assert resp.status_code == 401


async def test_export_rejects_wrong_secret_and_type(client, food):
    email = await _register_with_recipe(client, food)

    bad_secret = _mint(email, secret="not-the-secret")
    resp = await client.get(
        "/recipes/export", headers={"Authorization": f"Bearer {bad_secret}"}
    )
    assert resp.status_code == 401

    wrong_type = _mint(email, token_type="access")
    resp = await client.get(
        "/recipes/export", headers={"Authorization": f"Bearer {wrong_type}"}
    )
    assert resp.status_code == 401


async def test_export_disabled_without_secret(client, food):
    email = await _register_with_recipe(client, food)
    token = _mint(email)
    settings.cross_app_secret = None
    resp = await client.get("/recipes/export", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 401


async def test_export_unknown_email_401(client):
    resp = await client.get(
        "/recipes/export",
        headers={"Authorization": f"Bearer {_mint('nobody@plate.com')}"},
    )
    assert resp.status_code == 401

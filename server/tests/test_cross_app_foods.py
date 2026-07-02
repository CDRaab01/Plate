"""Cookbook-bound cross-app surface: POST /cross-app/resolve-foods + /cross-app/log-recipe."""

from datetime import date, datetime, timedelta, timezone

import pytest
from jose import jwt

from app.config import settings

SECRET = "test-cross-app-secret"


def _mint(email: str) -> str:
    return jwt.encode(
        {
            "email": email,
            "type": "cross_app",
            "exp": datetime.now(timezone.utc) + timedelta(seconds=60),
        },
        SECRET,
        algorithm="HS256",
    )


@pytest.fixture(autouse=True)
def cross_app_secret():
    old = settings.cross_app_secret
    settings.cross_app_secret = SECRET
    yield
    settings.cross_app_secret = old


async def _register(client) -> str:
    import uuid as _uuid

    uid = _uuid.uuid4().hex[:8]
    email = f"xapp_{uid}@plate.com"
    resp = await client.post(
        "/auth/register",
        json={"name": "XApp", "email": email, "password": "Testpass123!"},
    )
    assert resp.status_code == 201
    return email


async def test_resolve_matches_scales_and_flags(client, food):
    email = await _register(client)
    headers = {"Authorization": f"Bearer {_mint(email)}"}

    resp = await client.post(
        "/cross-app/resolve-foods",
        json={
            "items": [
                # `food` fixture: "Test Banana ...", 89 kcal / 100 g.
                {"name": food.name, "quantity": 200, "unit": "g"},
                {"name": "definitely-not-a-food-xyz", "quantity": 1, "unit": "g"},
                # A cup is density-dependent — flagged, not guessed.
                {"name": food.name, "quantity": 1, "unit": "cup"},
            ]
        },
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    items = resp.json()["items"]

    matched = items[0]
    assert matched["matched"] is True
    assert matched["food_name"] == food.name
    assert matched["kcal"] == pytest.approx(178.0)  # 89 * 2

    assert items[1]["matched"] is False
    assert items[2]["matched"] is False


async def test_resolve_pounds_canonicalized(client, food):
    email = await _register(client)
    headers = {"Authorization": f"Bearer {_mint(email)}"}

    resp = await client.post(
        "/cross-app/resolve-foods",
        json={"items": [{"name": food.name, "quantity": 1, "unit": "lb"}]},
        headers=headers,
    )
    item = resp.json()["items"][0]
    assert item["matched"] is True
    # 1 lb = 453.592 g -> 89 kcal/100g * 4.53592
    assert item["kcal"] == pytest.approx(89 * 4.53592, rel=1e-3)


async def test_log_recipe_creates_diary_entries(client, food):
    email = await _register(client)
    headers = {"Authorization": f"Bearer {_mint(email)}"}
    today = date.today().isoformat()

    resp = await client.post(
        "/cross-app/log-recipe",
        json={
            "date": today,
            "meal": "dinner",
            "recipe_name": "Cookbook Chili",
            "items": [
                {"name": food.name, "quantity": 150, "unit": "g"},
                {"name": "no-such-food-qqq", "quantity": 1, "unit": "g"},
            ],
        },
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"logged": 1, "skipped": 1}

    # The entry is visible in the user's own diary via normal auth.
    login = await client.post(
        "/auth/login", json={"email": email, "password": "Testpass123!"}
    )
    token = login.json()["access_token"]
    day = await client.get(
        "/log", params={"date": today}, headers={"Authorization": f"Bearer {token}"}
    )
    assert day.status_code == 200
    body = day.json()
    dinner = next(g for g in body["meals"] if g["meal"] == "dinner")
    assert any(e["food_name"] == food.name for e in dinner["entries"])


async def test_cross_app_rejects_normal_tokens(auth_client):
    resp = await auth_client.post("/cross-app/resolve-foods", json={"items": []})
    assert resp.status_code == 401

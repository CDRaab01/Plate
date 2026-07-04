"""GET /export — full per-user data export (ROADMAP T3 #6)."""


async def test_export_empty_user(auth_client):
    r = await auth_client.get("/export")
    assert r.status_code == 200, r.text
    assert "attachment" in r.headers.get("content-disposition", "")
    assert r.headers["content-disposition"].endswith('.json"')

    data = r.json()
    assert data["app"] == "plate"
    assert data["schema_version"] >= 1
    assert data["exported_at"]
    assert data["user"]["email"]
    assert "hashed_password" not in data["user"]
    assert "reset_token" not in data["user"]
    for key in (
        "user_goals",
        "body_metrics",
        "daily_targets",
        "food_log_entries",
        "recipes",
        "recipe_items",
    ):
        assert data[key] == [], key


async def test_export_includes_goal(auth_client):
    goal = await auth_client.put(
        "/goals",
        json={
            "goal_type": "cut",
            "weight_kg": 80.0,
            "height_cm": 180.0,
            "age": 30,
            "sex": "male",
            "activity_level": "moderate",
            "rate_kg_per_week": -0.5,
        },
    )
    assert goal.status_code == 200, goal.text

    data = (await auth_client.get("/export")).json()
    assert len(data["user_goals"]) == 1
    assert data["user_goals"][0]["goal_type"] == "cut"
    assert isinstance(data["user_goals"][0]["user_id"], str)  # UUID → string


async def test_export_requires_auth(client):
    assert (await client.get("/export")).status_code == 401

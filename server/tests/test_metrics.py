"""Bodyweight metrics + trend API tests (CLAUDE.md §10).

Confirms canonical-kg storage with lb/kg display, the unit preference round-trip, bounds, auth,
per-user isolation, and the trend endpoint. The trend math itself is covered in ``test_trend.py``.
"""
import pytest

from app.nutrition.units import KG_PER_LB

MALE_CUT = {
    "goal_type": "cut",
    "weight_kg": 90.0,
    "height_cm": 180.0,
    "age": 30,
    "sex": "male",
    "activity_level": "moderate",
    "rate_kg_per_week": -0.5,
}


async def test_log_weight_in_lb_stores_kg_and_displays_lb(auth_client):
    # Default unit_system is imperial → POST weight is lb, GET displays lb.
    resp = await auth_client.post("/metrics/weight", json={"date": "2026-06-01", "weight": 198.0})
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["unit"] == "lb"
    assert body["weight"] == pytest.approx(198.0, abs=0.01)
    assert body["weight_kg"] == pytest.approx(198.0 * KG_PER_LB, abs=0.01)


async def test_log_weight_explicit_kg_unit(auth_client):
    resp = await auth_client.post(
        "/metrics/weight", json={"date": "2026-06-01", "weight": 90.0, "unit": "kg"}
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["weight_kg"] == pytest.approx(90.0)


async def test_list_weight_ordered_oldest_first(auth_client):
    for d, w in [("2026-06-03", 196.0), ("2026-06-01", 198.0), ("2026-06-02", 197.0)]:
        await auth_client.post("/metrics/weight", json={"date": d, "weight": w})
    resp = await auth_client.get("/metrics/weight")
    assert resp.status_code == 200
    dates = [m["date"] for m in resp.json()]
    assert dates == ["2026-06-01", "2026-06-02", "2026-06-03"]


async def test_weight_bounds_rejected(auth_client):
    # 10 lb ≈ 4.5 kg, below the 20 kg floor → 422.
    resp = await auth_client.post("/metrics/weight", json={"date": "2026-06-01", "weight": 10.0})
    assert resp.status_code == 422


async def test_weight_requires_auth(client):
    resp = await client.post("/metrics/weight", json={"date": "2026-06-01", "weight": 198.0})
    assert resp.status_code == 401


async def test_weight_is_user_scoped(auth_client, client):
    await auth_client.post("/metrics/weight", json={"date": "2026-06-01", "weight": 198.0})

    import uuid

    uid = uuid.uuid4().hex[:8]
    r = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"other_{uid}@plate.com", "password": "Testpass123!"},
    )
    client.headers["Authorization"] = f"Bearer {r.json()['access_token']}"
    resp = await client.get("/metrics/weight")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_unit_preference_flips_display(auth_client):
    await auth_client.post("/metrics/weight", json={"date": "2026-06-01", "weight": 90.0, "unit": "kg"})
    # Switch to metric → reads come back in kg.
    await auth_client.patch("/users/me/settings", json={"unit_system": "metric"})
    resp = await auth_client.get("/metrics/weight")
    body = resp.json()[0]
    assert body["unit"] == "kg"
    assert body["weight"] == pytest.approx(90.0)


async def test_trend_status_insufficient_then_classified(auth_client):
    await auth_client.put("/goals", json=MALE_CUT)
    # No weigh-ins yet.
    resp = await auth_client.get("/metrics/weight/trend")
    assert resp.status_code == 200
    assert resp.json()["status"] == "insufficient_data"
    assert resp.json()["goal_rate_per_week"] == pytest.approx(-0.5 / KG_PER_LB, abs=0.01)  # lb/wk

    # A cut on pace: ~-0.5 kg/week over two weeks (logged in kg).
    for i in range(15):
        await auth_client.post(
            "/metrics/weight",
            json={
                "date": f"2026-06-{i + 1:02d}",
                "weight": 90.0 - 0.0714 * i,
                "unit": "kg",
            },
        )
    resp = await auth_client.get("/metrics/weight/trend")
    body = resp.json()
    assert body["status"] == "on_pace"
    assert body["trend_weight"] is not None
    assert len(body["points"]) == 15

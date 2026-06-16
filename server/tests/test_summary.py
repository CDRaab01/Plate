"""API tests for the weekly/range summary endpoint (Phase 8)."""
import datetime


async def test_summary_defaults_to_last_7_days(auth_client):
    resp = await auth_client.get("/log/summary")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["days"]) == 7
    today = datetime.date.today()
    assert body["end"] == today.isoformat()
    assert body["start"] == (today - datetime.timedelta(days=6)).isoformat()
    assert body["days"][-1]["date"] == today.isoformat()


async def test_summary_aggregates_per_day_and_total(auth_client, recipe_food):
    start = "2026-05-01"
    end = "2026-05-03"
    # 100g on day 1, 200g on day 3 (89 kcal/100g recipe_food).
    await auth_client.post(
        "/log",
        json={"food_id": str(recipe_food.id), "date": "2026-05-01", "meal": "lunch",
              "quantity": 100.0, "unit": "g"},
    )
    await auth_client.post(
        "/log",
        json={"food_id": str(recipe_food.id), "date": "2026-05-03", "meal": "dinner",
              "quantity": 200.0, "unit": "g"},
    )

    resp = await auth_client.get("/log/summary", params={"start": start, "end": end})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["days"]) == 3
    by_date = {d["date"]: d for d in body["days"]}
    assert by_date["2026-05-01"]["totals"]["kcal"] == 89.0
    assert by_date["2026-05-02"]["totals"]["kcal"] == 0.0  # zero-filled
    assert by_date["2026-05-03"]["totals"]["kcal"] == 178.0
    assert body["total"]["kcal"] == 267.0
    assert body["averages"]["kcal"] == 89.0  # 267 / 3 days
    # Every day carries a kcal target (static placeholder absent a goal).
    assert all(d["target_kcal"] > 0 for d in body["days"])


async def test_summary_rejects_start_after_end(auth_client):
    resp = await auth_client.get(
        "/log/summary", params={"start": "2026-05-10", "end": "2026-05-01"}
    )
    assert resp.status_code == 400


async def test_summary_rejects_oversized_range(auth_client):
    resp = await auth_client.get(
        "/log/summary", params={"start": "2026-01-01", "end": "2026-12-31"}
    )
    assert resp.status_code == 400


async def test_summary_requires_auth(client):
    resp = await client.get("/log/summary")
    assert resp.status_code == 401

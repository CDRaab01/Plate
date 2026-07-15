"""Weekly check-in composite: days logged this week, weight move, adaptive state."""

import datetime


async def _log(auth_client, food_id, day: datetime.date):
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food_id),
            "date": day.isoformat(),
            "meal": "lunch",
            "quantity": 100.0,
            "unit": "g",
        },
    )
    assert resp.status_code == 201, resp.text


async def _weigh(auth_client, day: datetime.date, kg: float):
    resp = await auth_client.post(
        "/metrics/weight", json={"date": day.isoformat(), "weight": kg, "unit": "kg"}
    )
    assert resp.status_code in (200, 201), resp.text


async def test_checkin_empty_for_new_user(auth_client):
    body = (await auth_client.get("/checkin/weekly")).json()
    assert body["days_logged"] == 0
    assert body["weight_change_kg"] is None
    assert body["days_in_window"] == 7
    assert body["adaptive"] is None  # no goal set yet → no adaptive read


async def test_checkin_counts_distinct_days_logged_this_week(auth_client, food):
    today = datetime.date.today()
    # today, yesterday twice (same day), and three days ago = 3 distinct days.
    for offset in (0, 1, 1, 3):
        await _log(auth_client, food.id, today - datetime.timedelta(days=offset))
    # A log outside the 7-day window must NOT count.
    await _log(auth_client, food.id, today - datetime.timedelta(days=10))

    body = (await auth_client.get("/checkin/weekly")).json()
    assert body["days_logged"] == 3


async def test_checkin_weight_change_over_the_week(auth_client):
    today = datetime.date.today()
    await _weigh(auth_client, today - datetime.timedelta(days=8), 80.0)
    await _weigh(auth_client, today, 79.2)

    body = (await auth_client.get("/checkin/weekly")).json()
    assert body["weight_change_kg"] == round(79.2 - 80.0, 2)  # down 0.8 kg


async def test_checkin_weight_change_null_with_one_weigh_in(auth_client):
    await _weigh(auth_client, datetime.date.today(), 80.0)
    body = (await auth_client.get("/checkin/weekly")).json()
    assert body["weight_change_kg"] is None  # need two weigh-ins to compare

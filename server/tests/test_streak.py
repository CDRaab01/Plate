"""Logging streak: consecutive days logged, surfaced on the day view (relative to today)."""

import datetime


async def _log_on(auth_client, food_id, day: datetime.date):
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


async def _streak(auth_client) -> int:
    return (await auth_client.get("/log")).json()["streak"]


async def test_streak_zero_for_new_user(auth_client):
    assert await _streak(auth_client) == 0


async def test_streak_counts_consecutive_days_including_today(auth_client, food):
    today = datetime.date.today()
    for offset in (0, 1, 2):  # today + the two prior days
        await _log_on(auth_client, food.id, today - datetime.timedelta(days=offset))
    assert await _streak(auth_client) == 3


async def test_streak_survives_a_grace_day_when_today_not_logged_yet(auth_client, food):
    today = datetime.date.today()
    for offset in (1, 2):  # yesterday + the day before, but NOT today
        await _log_on(auth_client, food.id, today - datetime.timedelta(days=offset))
    assert await _streak(auth_client) == 2  # still alive via the one grace day


async def test_streak_broken_by_a_gap_is_zero(auth_client, food):
    today = datetime.date.today()
    # Only logged three days ago — neither today nor yesterday, so the run is dead.
    await _log_on(auth_client, food.id, today - datetime.timedelta(days=3))
    assert await _streak(auth_client) == 0


async def test_streak_stops_at_the_first_gap(auth_client, food):
    today = datetime.date.today()
    # today, yesterday logged; a gap at day-2; then day-3 logged (doesn't extend the run).
    for offset in (0, 1, 3):
        await _log_on(auth_client, food.id, today - datetime.timedelta(days=offset))
    assert await _streak(auth_client) == 2

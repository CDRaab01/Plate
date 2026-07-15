"""Weekly check-in: a once-a-week composite of consistency, weight movement, and adaptive state.

Assembles existing signals — days logged in the last week, the scale's move over ~7 days, and the
adaptive-TDEE read — into one snapshot the client presents as a Sunday ritual. Pure DB reads; no
new state.
"""

import datetime
import uuid

from sqlalchemy import distinct, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.models.food_log_entry import FoodLogEntry
from app.schemas.goal import WeeklyCheckinOut
from app.services.adaptive_service import compute_adaptive_for

WINDOW_DAYS = 7


async def get_weekly_checkin(db: AsyncSession, user_id: uuid.UUID) -> WeeklyCheckinOut:
    today = datetime.date.today()
    since = today - datetime.timedelta(days=WINDOW_DAYS - 1)

    # Distinct days with any log in the last 7 (inclusive of today) — the consistency signal.
    logged = await db.execute(
        select(distinct(FoodLogEntry.date)).where(
            FoodLogEntry.user_id == user_id,
            FoodLogEntry.date >= since,
            FoodLogEntry.date <= today,
        )
    )
    days_logged = len(logged.all())

    # Weight move over ~a week: latest weigh-in minus the most recent one on/before a week earlier
    # (falling back to the oldest we have). Canonical kg; the client converts to the display unit.
    rows = await db.execute(
        select(BodyMetric)
        .where(BodyMetric.user_id == user_id)
        .order_by(BodyMetric.date.asc(), BodyMetric.created_at.asc())
    )
    metrics = list(rows.scalars().all())
    weight_change_kg: float | None = None
    if len(metrics) >= 2:
        latest = metrics[-1]
        week_ago = latest.date - datetime.timedelta(days=WINDOW_DAYS)
        prior = next((m for m in reversed(metrics[:-1]) if m.date <= week_ago), metrics[0])
        weight_change_kg = round(latest.weight - prior.weight, 2)

    adaptive = await compute_adaptive_for(db, user_id, today)
    return WeeklyCheckinOut(
        days_logged=days_logged,
        weight_change_kg=weight_change_kg,
        adaptive=adaptive,
    )

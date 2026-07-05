"""Adaptive TDEE correction — the data-gathering half (ROADMAP2 T3 #1).

Loads the trailing window of food logs (summed kcal per day) and weigh-ins for a user, computes the
formula TDEE from their active goal + current bodyweight, and runs the pure engine in
:mod:`app.nutrition.adaptive`. Kept out of the pure module so that stays a function of its inputs;
kept out of ``goal_service`` so the DB reads live in one place. Feeds both ``GET /goals/adaptive``
and the target computation (``goal_service.compute_targets_for``).
"""

import datetime
import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.models.food_log_entry import FoodLogEntry
from app.nutrition import constants as c
from app.nutrition.adaptive import AdaptiveTDEE, compute_adaptive_tdee
from app.nutrition.targets import from_goal, tdee
from app.nutrition.trend import WeightPoint


async def compute_adaptive_for(
    db: AsyncSession,
    user_id: uuid.UUID,
    day: datetime.date,
    *,
    window_days: int = c.ADAPTIVE_WINDOW_DAYS,
) -> AdaptiveTDEE | None:
    """The adaptive-TDEE state for ``user_id`` as of ``day``, or ``None`` if no goal is set.

    Reads the trailing ``window_days`` of food logs (grouped ``sum(kcal)`` per date) and weigh-ins,
    derives the formula maintenance from the active goal + latest weigh-in (pre goal-adjustment,
    exactly what the targets engine anchors on), and runs the pure correction.
    """
    # Lazy imports: goal_service / metric_service both participate in the adaptive-targets cycle.
    from app.services.goal_service import get_active_goal
    from app.services.metric_service import latest_weight_kg_on_or_before

    goal = await get_active_goal(db, user_id)
    if goal is None:
        return None

    start = day - datetime.timedelta(days=window_days - 1)

    # Per-day intake over the window: grouped sum of the denormalized kcal snapshots.
    intake_rows = await db.execute(
        select(FoodLogEntry.date, func.sum(FoodLogEntry.kcal))
        .where(
            FoodLogEntry.user_id == user_id,
            FoodLogEntry.date >= start,
            FoodLogEntry.date <= day,
        )
        .group_by(FoodLogEntry.date)
    )
    daily_intakes = {d: float(total or 0.0) for d, total in intake_rows.all()}

    # Weigh-ins over the same window.
    weight_rows = await db.execute(
        select(BodyMetric.date, BodyMetric.weight)
        .where(
            BodyMetric.user_id == user_id,
            BodyMetric.date >= start,
            BodyMetric.date <= day,
        )
        .order_by(BodyMetric.date.asc())
    )
    weight_points = [WeightPoint(date=d, weight=w) for d, w in weight_rows.all()]

    # Formula maintenance from the goal + current bodyweight (BMR × activity, pre goal-adjustment).
    weight_kg = await latest_weight_kg_on_or_before(db, user_id, day)
    profile = from_goal(goal, weight_kg=weight_kg)
    formula_tdee = tdee(
        profile.weight_kg,
        profile.height_cm,
        profile.age,
        profile.sex,
        profile.activity_level,
    )

    return compute_adaptive_tdee(
        daily_intakes, weight_points, formula_tdee, window_days=window_days
    )

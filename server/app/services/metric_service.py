"""Bodyweight metrics: weigh-in logging, the weight series, and the latest-weight lookup the
targets engine uses.

Weight is stored canonical in **kg**; inbound values are converted from the request's unit here, at
the edge, before the kg sanity bounds are applied. Read helpers return ORM rows (kg); the router
formats them to the user's display unit.
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import BODY_WEIGHT_BOUNDS_KG
from app.models.body_metric import BodyMetric
from app.nutrition.trend import WeightPoint, WeightTrend, compute_trend
from app.nutrition.units import weight_to_kg
from app.schemas.metric import BodyMetricCreate

_W_MIN, _W_MAX = BODY_WEIGHT_BOUNDS_KG


async def get_weight_metrics(db: AsyncSession, user_id: uuid.UUID) -> list[BodyMetric]:
    """All of the user's weigh-ins, oldest first (ready for trend/charting)."""
    result = await db.execute(
        select(BodyMetric)
        .where(BodyMetric.user_id == user_id)
        .order_by(BodyMetric.date.asc(), BodyMetric.created_at.asc())
    )
    return list(result.scalars().all())


async def add_weight_metric(
    db: AsyncSession, user_id: uuid.UUID, req: BodyMetricCreate
) -> BodyMetric:
    """Convert the weigh-in to canonical kg, bound-check it, and persist it."""
    weight_kg = weight_to_kg(req.weight, req.unit)
    if not (_W_MIN <= weight_kg <= _W_MAX):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"weight out of range ({_W_MIN}–{_W_MAX} kg)",
        )
    metric = BodyMetric(user_id=user_id, date=req.date, weight=weight_kg, bodyfat=req.bodyfat)
    db.add(metric)
    await db.commit()
    await db.refresh(metric)
    return metric


async def latest_weight_kg_on_or_before(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date
) -> float | None:
    """The most recent weigh-in weight (kg) dated on or before ``day``, or ``None`` if there is none.

    Used by the targets engine so a day's targets use the weight in effect *then* (historical
    summary days included), not merely the latest weigh-in ever.
    """
    result = await db.execute(
        select(BodyMetric.weight)
        .where(BodyMetric.user_id == user_id, BodyMetric.date <= day)
        .order_by(BodyMetric.date.desc(), BodyMetric.created_at.desc())
        .limit(1)
    )
    return result.scalars().first()


async def weight_change_kg_in_window(
    db: AsyncSession, user_id: uuid.UUID, start: datetime.date, end: datetime.date
) -> float | None:
    """Latest weigh-in minus the earliest one (canonical kg) within ``[start, end]`` inclusive.

    ``None`` when the window holds fewer than two weigh-ins (no change is computable). Ties on a
    date break by insertion order, so same-day weigh-ins still yield a well-defined first/last.
    """
    result = await db.execute(
        select(BodyMetric.weight)
        .where(
            BodyMetric.user_id == user_id,
            BodyMetric.date >= start,
            BodyMetric.date <= end,
        )
        .order_by(BodyMetric.date.asc(), BodyMetric.created_at.asc())
    )
    weights = list(result.scalars().all())
    if len(weights) < 2:
        return None
    return weights[-1] - weights[0]


async def compute_weight_trend(db: AsyncSession, user_id: uuid.UUID) -> WeightTrend:
    """Build the user's weight trend (smoothed series + observed rate + on-pace status).

    The goal rate it's compared against is the active goal's ``rate_kg_per_week`` (0.0 if no goal).
    """
    # Lazy import: goal_service imports this module (adaptive targets), so importing it at module
    # load would be circular.
    from app.services.goal_service import get_active_goal

    metrics = await get_weight_metrics(db, user_id)
    points = [WeightPoint(date=m.date, weight=m.weight) for m in metrics]
    goal = await get_active_goal(db, user_id)
    goal_rate = goal.rate_kg_per_week if goal is not None else 0.0
    return compute_trend(points, goal_rate)

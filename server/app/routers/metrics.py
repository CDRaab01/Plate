"""Bodyweight metrics + weight trend (Home dashboard / adaptive targets).

* ``GET  /metrics/weight``       — the user's weigh-ins, oldest first.
* ``POST /metrics/weight``       — log a weigh-in (accepts lb or kg; stored canonical in kg).
* ``GET  /metrics/weight/trend`` — smoothed trend + observed weekly rate + on-pace status.

Weights and rates are returned in the user's preferred unit (lb/kg); ``weight_kg`` on each weigh-in
is the canonical value, stable across the toggle, for charting.
"""

from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.body_metric import BodyMetric
from app.nutrition.trend import WeightTrend
from app.nutrition.units import WEIGHT_UNIT_FOR_SYSTEM, kg_to_display
from app.schemas.metric import (
    BodyMetricCreate,
    BodyMetricOut,
    WeightTrendOut,
    WeightTrendPoint,
)
from app.security import CurrentUser
from app.services.metric_service import (
    add_weight_metric,
    compute_weight_trend,
    get_weight_metrics,
)
from app.services.user_service import get_unit_system

router = APIRouter(prefix="/metrics", tags=["metrics"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


def _metric_out(metric: BodyMetric, unit_system: str) -> BodyMetricOut:
    return BodyMetricOut(
        id=metric.id,
        user_id=metric.user_id,
        date=metric.date,
        weight_kg=metric.weight,
        weight=kg_to_display(metric.weight, unit_system),
        unit=WEIGHT_UNIT_FOR_SYSTEM[unit_system],
        bodyfat=metric.bodyfat,
    )


def _trend_out(trend: WeightTrend, unit_system: str) -> WeightTrendOut:
    unit = WEIGHT_UNIT_FOR_SYSTEM[unit_system]

    def disp(kg: float | None) -> float | None:
        return None if kg is None else kg_to_display(kg, unit_system)

    return WeightTrendOut(
        points=[
            WeightTrendPoint(date=p.date, weight=kg_to_display(p.weight, unit_system))
            for p in trend.points
        ],
        trend_weight=disp(trend.trend_weight_kg),
        observed_rate_per_week=disp(trend.observed_rate_kg_per_week),
        goal_rate_per_week=kg_to_display(trend.goal_rate_kg_per_week, unit_system),
        unit=unit,
        status=trend.status,
    )


@router.get("/weight", response_model=list[BodyMetricOut])
async def list_weight(current_user: CurrentUser, db: DbSession):
    unit_system = get_unit_system(current_user)
    metrics = await get_weight_metrics(db, current_user.id)
    return [_metric_out(m, unit_system) for m in metrics]


@router.post("/weight", response_model=BodyMetricOut, status_code=201)
@limiter.limit("30/minute")
async def log_weight(
    request: Request,
    req: BodyMetricCreate,
    current_user: CurrentUser,
    db: DbSession,
):
    metric = await add_weight_metric(db, current_user.id, req)
    return _metric_out(metric, get_unit_system(current_user))


@router.get("/weight/trend", response_model=WeightTrendOut)
async def weight_trend(current_user: CurrentUser, db: DbSession):
    trend = await compute_weight_trend(db, current_user.id)
    return _trend_out(trend, get_unit_system(current_user))

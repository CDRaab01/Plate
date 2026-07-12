"""Cross-app surface for the sister app **Cookbook** (recipe nutrition + log-to-diary).

Same trust model as ``GET /workouts`` (Spotter-bound) and ``GET /recipes/export``: a short-lived
JWT signed with ``CROSS_APP_SECRET`` carrying the user's email, validated by
``get_cross_app_user`` — never Plate's own session tokens. Disabled (401) unless the secret is
set. Kept in its own router so the whole cross-app surface is auditable in isolation.
"""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.schemas.cross_app import (
    CrossAppLogRequest,
    CrossAppLogResponse,
    ResolveFoodsRequest,
    ResolveFoodsResponse,
)
from app.security import CrossAppUser
from app.services.cross_app_food_service import log_cross_app_recipe, resolve_foods
from app.services.log_service import remaining_macros

router = APIRouter(prefix="/cross-app", tags=["cross-app"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.post("/resolve-foods", response_model=ResolveFoodsResponse)
@limiter.limit("60/minute")
async def resolve(
    request: Request,
    req: ResolveFoodsRequest,
    current_user: CrossAppUser,
    db: DbSession,
):
    """Best-effort macro estimates for free-text ingredients (unmatched items come back flagged)."""
    return await resolve_foods(db, req)


@router.post("/log-recipe", response_model=CrossAppLogResponse)
@limiter.limit("60/minute")
async def log_recipe(
    request: Request,
    req: CrossAppLogRequest,
    current_user: CrossAppUser,
    db: DbSession,
):
    """Log resolvable items into the user's diary (one snapshotted entry per match)."""
    return await log_cross_app_recipe(db, current_user.id, req)


@router.get("/remaining")
@limiter.limit("60/minute")
async def remaining(
    request: Request,
    current_user: CrossAppUser,
    db: DbSession,
    date: datetime.date = Query(..., description="The day to report remaining macros for"),
):
    """Macros left today (federated awareness Link F) — Cookbook ranks recipes that fit. 404 when
    the user has no active goal (no targets to subtract against); the consumer degrades to no badge."""
    result = await remaining_macros(db, current_user.id, date)
    if result is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No active goal — no remaining targets")
    return {"date": date.isoformat(), **result}

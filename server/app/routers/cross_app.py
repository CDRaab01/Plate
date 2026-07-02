"""Cross-app surface for the sister app **Cookbook** (recipe nutrition + log-to-diary).

Same trust model as ``GET /workouts`` (Spotter-bound) and ``GET /recipes/export``: a short-lived
JWT signed with ``CROSS_APP_SECRET`` carrying the user's email, validated by
``get_cross_app_user`` — never Plate's own session tokens. Disabled (401) unless the secret is
set. Kept in its own router so the whole cross-app surface is auditable in isolation.
"""

from typing import Annotated

from fastapi import APIRouter, Depends, Request
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

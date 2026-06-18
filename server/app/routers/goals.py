"""Goals + computed daily targets (CLAUDE.md §7).

* ``PUT /goals`` — set/replace the active goal (appends a history row).
* ``GET /goals`` — read the active goal.
* ``GET /goals/targets?date=`` — the computed kcal/macro targets for a date.
"""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.goal import GoalOut, GoalUpsert, TargetsOut
from app.security import CurrentUser
from app.services.goal_service import compute_targets_for, get_active_goal, set_goal
from app.services.workout_source import WorkoutSource, get_workout_source, is_training_day

router = APIRouter(prefix="/goals", tags=["goals"])

DbSession = Annotated[AsyncSession, Depends(get_db)]
Workouts = Annotated[WorkoutSource, Depends(get_workout_source)]


@router.put("", response_model=GoalOut)
async def upsert_goal(
    req: GoalUpsert,
    current_user: CurrentUser,
    db: DbSession,
):
    """Set the user's goal. Each call appends a new row; the most recent is the active goal."""
    return await set_goal(db, current_user.id, req)


@router.get("", response_model=GoalOut)
async def read_goal(
    current_user: CurrentUser,
    db: DbSession,
):
    goal = await get_active_goal(db, current_user.id)
    if goal is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No goal set yet")
    return goal


@router.get("/targets", response_model=TargetsOut)
async def read_targets(
    current_user: CurrentUser,
    db: DbSession,
    workouts: Workouts,
    date: Annotated[datetime.date | None, Query(description="Defaults to today (UTC)")] = None,
):
    """Compute the day's kcal/macro targets from the active goal. 404 until a goal is set.

    Targets include the training-day bump (Spotter-awareness, §7) when the user trained that day.
    """
    target_day = date or datetime.date.today()
    trained = await is_training_day(current_user.email, target_day, source=workouts)
    targets = await compute_targets_for(db, current_user.id, target_day, trained=trained)
    if targets is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Set a goal to get personalized targets",
        )
    return TargetsOut(
        date=target_day,
        kcal=targets.kcal,
        protein_g=targets.protein_g,
        carbs_g=targets.carbs_g,
        fat_g=targets.fat_g,
        trained_today=trained,
    )

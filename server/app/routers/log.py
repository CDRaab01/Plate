import datetime
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from fastapi import HTTPException

from app.database import get_db
from app.schemas.log import (
    DailyLog,
    LogEntryCreate,
    LogEntryOut,
    LogEntryUpdate,
    QuickAddCreate,
    RangeSummaryOut,
)
from app.security import CurrentUser
from app.services.log_service import (
    create_entry,
    create_quick_add,
    delete_entry,
    get_day,
    get_summary,
    update_entry,
)
from app.services.workout_source import WorkoutSource, get_workout_source, is_training_day

MAX_SUMMARY_DAYS = 31

router = APIRouter(prefix="/log", tags=["log"])

DbSession = Annotated[AsyncSession, Depends(get_db)]
Workouts = Annotated[WorkoutSource, Depends(get_workout_source)]


@router.post("", response_model=LogEntryOut, status_code=status.HTTP_201_CREATED)
async def create(
    req: LogEntryCreate,
    current_user: CurrentUser,
    db: DbSession,
):
    return await create_entry(db, current_user.id, req)


@router.get("", response_model=DailyLog)
async def day(
    current_user: CurrentUser,
    db: DbSession,
    workouts: Workouts,
    date: Annotated[datetime.date | None, Query(description="Defaults to today (UTC)")] = None,
):
    target_day = date or datetime.date.today()
    trained = await is_training_day(current_user.email, target_day, source=workouts)
    return await get_day(db, current_user.id, target_day, trained=trained)


@router.post("/quick-add", response_model=LogEntryOut, status_code=status.HTTP_201_CREATED)
async def quick_add(
    req: QuickAddCreate,
    current_user: CurrentUser,
    db: DbSession,
):
    """Log raw macros directly, with no source food (MyFitnessPal-style quick add)."""
    return await create_quick_add(db, current_user.id, req)


@router.get("/summary", response_model=RangeSummaryOut)
async def summary(
    current_user: CurrentUser,
    db: DbSession,
    workouts: Workouts,
    start: Annotated[datetime.date | None, Query(description="Range start (inclusive)")] = None,
    end: Annotated[datetime.date | None, Query(description="Range end (inclusive)")] = None,
):
    """Weekly (or arbitrary-range) summary: per-day totals + period total and daily averages.

    Defaults to the last 7 days inclusive of today. The span is capped at 31 days.
    """
    today = datetime.date.today()
    end_day = end or today
    start_day = start or (end_day - datetime.timedelta(days=6))
    if start_day > end_day:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="start must be <= end")
    if (end_day - start_day).days + 1 > MAX_SUMMARY_DAYS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"range too large (max {MAX_SUMMARY_DAYS} days)",
        )

    training_days: set[datetime.date] = set()
    for offset in range((end_day - start_day).days + 1):
        day_i = start_day + datetime.timedelta(days=offset)
        if await is_training_day(current_user.email, day_i, source=workouts):
            training_days.add(day_i)

    return await get_summary(db, current_user.id, start_day, end_day, training_days=training_days)


@router.put("/{entry_id}", response_model=LogEntryOut)
async def update(
    entry_id: uuid.UUID,
    req: LogEntryUpdate,
    current_user: CurrentUser,
    db: DbSession,
):
    return await update_entry(db, current_user.id, entry_id, req)


@router.delete("/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete(
    entry_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
):
    await delete_entry(db, current_user.id, entry_id)

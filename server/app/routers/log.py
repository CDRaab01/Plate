import datetime
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.log import DailyLog, LogEntryCreate, LogEntryOut, LogEntryUpdate
from app.security import CurrentUser
from app.services.log_service import (
    create_entry,
    delete_entry,
    get_day,
    update_entry,
)

router = APIRouter(prefix="/log", tags=["log"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


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
    date: Annotated[datetime.date | None, Query(description="Defaults to today (UTC)")] = None,
):
    target_day = date or datetime.date.today()
    return await get_day(db, current_user.id, target_day)


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

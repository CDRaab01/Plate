from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.goal import WeeklyCheckinOut
from app.security import CurrentUser
from app.services.checkin_service import get_weekly_checkin

router = APIRouter(prefix="/checkin", tags=["checkin"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/weekly", response_model=WeeklyCheckinOut)
async def weekly(current_user: CurrentUser, db: DbSession):
    """The weekly check-in snapshot: days logged this week, the scale's move, and adaptive state."""
    return await get_weekly_checkin(db, current_user.id)

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.user import UserOut, UserSettingsUpdate
from app.security import CurrentUser
from app.services.user_service import get_unit_system, set_unit_system

router = APIRouter(prefix="/users", tags=["users"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


def _user_out(user) -> UserOut:
    """Serialize a user, resolving the unit_system from its settings JSON."""
    return UserOut(
        id=user.id,
        name=user.name,
        email=user.email,
        unit_system=get_unit_system(user),
    )


@router.get("/me", response_model=UserOut)
async def me(current_user: CurrentUser):
    return _user_out(current_user)


@router.patch("/me/settings", response_model=UserOut)
async def update_settings(
    req: UserSettingsUpdate,
    current_user: CurrentUser,
    db: DbSession,
):
    """Update the signed-in user's preferences (currently just the lb/kg unit system)."""
    user = await set_unit_system(db, current_user, req.unit_system)
    return _user_out(user)

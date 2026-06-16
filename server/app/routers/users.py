from app.security import CurrentUser
from fastapi import APIRouter

from app.schemas.user import UserOut

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut)
async def me(current_user: CurrentUser):
    return current_user

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.food import FoodCreate, FoodOut
from app.security import CurrentUser
from app.services.food_service import create_custom_food, get_food, search_foods

router = APIRouter(prefix="/foods", tags=["foods"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/search", response_model=list[FoodOut])
async def search(
    current_user: CurrentUser,
    db: DbSession,
    q: Annotated[str, Query(min_length=1, description="Search text")],
):
    """Local-cache-first food search. External sources are hit only on a cache miss."""
    return await search_foods(db, q)


@router.post("", response_model=FoodOut, status_code=status.HTTP_201_CREATED)
async def create_food(
    req: FoodCreate,
    current_user: CurrentUser,
    db: DbSession,
):
    """Create a user-defined custom food for items not found in USDA/OFF."""
    return await create_custom_food(db, req.model_dump())


@router.get("/{food_id}", response_model=FoodOut)
async def read_food(
    food_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
):
    food = await get_food(db, food_id)
    if food is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Food not found")
    return food

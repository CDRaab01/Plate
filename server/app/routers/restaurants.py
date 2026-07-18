import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.log import LogEntryOut
from app.schemas.restaurant import (
    RestaurantComponentsReplace,
    RestaurantCreate,
    RestaurantLogRequest,
    RestaurantOut,
    RestaurantUpdate,
)
from app.security import CurrentUser
from app.services.restaurant_service import (
    create_restaurant,
    delete_restaurant,
    get_restaurant,
    list_restaurants,
    log_restaurant,
    replace_components,
    update_restaurant,
)

router = APIRouter(prefix="/restaurants", tags=["restaurants"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.post("", response_model=RestaurantOut, status_code=status.HTTP_201_CREATED)
async def create(req: RestaurantCreate, current_user: CurrentUser, db: DbSession):
    return await create_restaurant(db, current_user.id, req)


@router.get("", response_model=list[RestaurantOut])
async def list_all(current_user: CurrentUser, db: DbSession):
    """The caller's restaurants plus other accounts' shared ones (own first)."""
    return await list_restaurants(db, current_user.id)


@router.get("/{restaurant_id}", response_model=RestaurantOut)
async def read(restaurant_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await get_restaurant(db, current_user.id, restaurant_id)


@router.patch("/{restaurant_id}", response_model=RestaurantOut)
async def patch(
    restaurant_id: uuid.UUID, req: RestaurantUpdate, current_user: CurrentUser, db: DbSession
):
    return await update_restaurant(db, current_user.id, restaurant_id, req)


@router.put("/{restaurant_id}/components", response_model=RestaurantOut)
async def put_components(
    restaurant_id: uuid.UUID,
    req: RestaurantComponentsReplace,
    current_user: CurrentUser,
    db: DbSession,
):
    return await replace_components(db, current_user.id, restaurant_id, req.components)


@router.delete("/{restaurant_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete(restaurant_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await delete_restaurant(db, current_user.id, restaurant_id)


@router.post(
    "/{restaurant_id}/log", response_model=list[LogEntryOut], status_code=status.HTTP_201_CREATED
)
async def log(
    restaurant_id: uuid.UUID, req: RestaurantLogRequest, current_user: CurrentUser, db: DbSession
):
    """Log the ticked components into the caller's day for the given date + meal."""
    return await log_restaurant(db, current_user.id, restaurant_id, req)

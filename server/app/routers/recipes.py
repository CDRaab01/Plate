import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.schemas.log import LogEntryOut
from app.schemas.recipe import (
    DiscoveredRecipe,
    RecipeCreate,
    RecipeExport,
    RecipeImportRequest,
    RecipeItemsReplace,
    RecipeLogRequest,
    RecipeOut,
    RecipeUpdate,
)
from app.security import CrossAppUser, CurrentUser
from app.services.recipe_discovery_service import discover_recipes, import_recipe
from app.services.recipe_service import (
    create_recipe,
    delete_recipe,
    export_recipes,
    get_recipe,
    list_recipes,
    log_recipe,
    replace_items,
    update_recipe,
)

router = APIRouter(prefix="/recipes", tags=["recipes"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/discover", response_model=list[DiscoveredRecipe])
@limiter.limit("30/minute")
async def discover(
    request: Request,
    current_user: CurrentUser,
    q: Annotated[str, Query(description="Recipe search text")],
):
    """Search external recipes (Spoonacular). 503 until SPOONACULAR_API_KEY is configured."""
    hits = await discover_recipes(q)
    return [
        DiscoveredRecipe(
            source_id=h.source_id,
            title=h.title,
            image=h.image,
            ready_in_minutes=h.ready_in_minutes,
            servings=h.servings,
        )
        for h in hits
    ]


@router.post("/import", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("30/minute")
async def import_external(
    request: Request,
    req: RecipeImportRequest,
    current_user: CurrentUser,
    db: DbSession,
):
    """Import an external recipe as a saved Plate recipe (ingredients become loggable foods)."""
    return await import_recipe(db, current_user.id, req.source_id)


@router.get("/export", response_model=list[RecipeExport])
@limiter.limit("60/minute")
async def export(request: Request, current_user: CrossAppUser, db: DbSession):
    """**Cross-app**, read-only recipe export for the sister app **Cookbook** (its recipe
    migration). Not Plate's own user-token auth — takes a cross-app JWT signed with
    ``CROSS_APP_SECRET`` carrying the user's email (see ``get_cross_app_user``). Disabled
    (401) unless the secret is set. Declared before ``/{recipe_id}`` so "export" never parses
    as a recipe id."""
    return await export_recipes(db, current_user.id)


@router.post("", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
async def create(req: RecipeCreate, current_user: CurrentUser, db: DbSession):
    return await create_recipe(db, current_user.id, req)


@router.get("", response_model=list[RecipeOut])
async def list_all(current_user: CurrentUser, db: DbSession):
    return await list_recipes(db, current_user.id)


@router.get("/{recipe_id}", response_model=RecipeOut)
async def read(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await get_recipe(db, current_user.id, recipe_id)


@router.patch("/{recipe_id}", response_model=RecipeOut)
async def patch(recipe_id: uuid.UUID, req: RecipeUpdate, current_user: CurrentUser, db: DbSession):
    return await update_recipe(db, current_user.id, recipe_id, req)


@router.put("/{recipe_id}/items", response_model=RecipeOut)
async def put_items(
    recipe_id: uuid.UUID, req: RecipeItemsReplace, current_user: CurrentUser, db: DbSession
):
    return await replace_items(db, current_user.id, recipe_id, req.items)


@router.delete("/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await delete_recipe(db, current_user.id, recipe_id)


@router.post(
    "/{recipe_id}/log", response_model=list[LogEntryOut], status_code=status.HTTP_201_CREATED
)
async def log(
    recipe_id: uuid.UUID, req: RecipeLogRequest, current_user: CurrentUser, db: DbSession
):
    """Expand the recipe into the day's log (one entry per item) for the given date + meal."""
    return await log_recipe(db, current_user.id, recipe_id, req.date, req.meal)

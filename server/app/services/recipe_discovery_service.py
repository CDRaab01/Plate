"""Discover external recipes and import one as a saved Plate recipe.

Each imported ingredient becomes a ``Food`` (tagged ``source='spoonacular'``, excluded from normal
food search) carrying that ingredient's macros, linked as a ``recipe_item``. So an imported recipe
is an ordinary :class:`~app.models.recipe.Recipe` — the existing ``/recipes/{id}/log`` adds all its
parts to a meal, and it shows up on the Recipes screen for later.
"""

import logging
import uuid

import httpx
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.food import Food
from app.models.recipe import Recipe
from app.models.recipe_item import RecipeItem
from app.recipes_ext.base import NormalizedIngredient, RecipeSource, RecipeSummary
from app.recipes_ext.spoonacular import SpoonacularSource
from app.schemas.recipe import RecipeOut
from app.services.recipe_service import _to_out

log = logging.getLogger(__name__)

_DISABLED = HTTPException(
    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
    detail="Recipe discovery is not configured (set SPOONACULAR_API_KEY).",
)


def _scaled(value: float | None, factor: float) -> float | None:
    return None if value is None else value * factor


def _ingredient_to_food(ing: NormalizedIngredient) -> tuple[Food, float, str]:
    """Build a Food for one ingredient plus the (quantity, unit) to log it by.

    With a gram weight we store a per-100g basis and log by grams; otherwise the ingredient's macros
    are stored as one serving and logged as 1 serving. Either way the logged macros equal the
    ingredient's contribution.
    """
    if ing.grams and ing.grams > 0:
        f = 100.0 / ing.grams
        food = Food(
            source="spoonacular",
            name=ing.name,
            serving_size=ing.grams,
            serving_unit="g",
            kcal_per_100g=ing.kcal * f,
            protein_g_per_100g=ing.protein_g * f,
            carbs_g_per_100g=ing.carbs_g * f,
            fat_g_per_100g=ing.fat_g * f,
            fiber_g_per_100g=_scaled(ing.fiber_g, f),
            sugar_g_per_100g=_scaled(ing.sugar_g, f),
            sat_fat_g_per_100g=_scaled(ing.sat_fat_g, f),
            cholesterol_mg_per_100g=_scaled(ing.cholesterol_mg, f),
            sodium_mg_per_100g=_scaled(ing.sodium_mg, f),
            kcal_per_serving=ing.kcal,
            protein_g_per_serving=ing.protein_g,
            carbs_g_per_serving=ing.carbs_g,
            fat_g_per_serving=ing.fat_g,
            fiber_g_per_serving=ing.fiber_g,
            sugar_g_per_serving=ing.sugar_g,
            sat_fat_g_per_serving=ing.sat_fat_g,
            cholesterol_mg_per_serving=ing.cholesterol_mg,
            sodium_mg_per_serving=ing.sodium_mg,
        )
        return food, ing.grams, "g"

    # No gram weight: store the ingredient's macros as a single serving. per-100g is filled with the
    # same values (unused — we always log this by serving) so the non-null columns are satisfied.
    food = Food(
        source="spoonacular",
        name=ing.name,
        kcal_per_100g=ing.kcal,
        protein_g_per_100g=ing.protein_g,
        carbs_g_per_100g=ing.carbs_g,
        fat_g_per_100g=ing.fat_g,
        fiber_g_per_100g=ing.fiber_g,
        sugar_g_per_100g=ing.sugar_g,
        sat_fat_g_per_100g=ing.sat_fat_g,
        cholesterol_mg_per_100g=ing.cholesterol_mg,
        sodium_mg_per_100g=ing.sodium_mg,
        kcal_per_serving=ing.kcal,
        protein_g_per_serving=ing.protein_g,
        carbs_g_per_serving=ing.carbs_g,
        fat_g_per_serving=ing.fat_g,
        fiber_g_per_serving=ing.fiber_g,
        sugar_g_per_serving=ing.sugar_g,
        sat_fat_g_per_serving=ing.sat_fat_g,
        cholesterol_mg_per_serving=ing.cholesterol_mg,
        sodium_mg_per_serving=ing.sodium_mg,
    )
    return food, 1.0, "serving"


async def discover_recipes(
    query: str,
    *,
    source: RecipeSource | None = None,
) -> list[RecipeSummary]:
    """Search external recipes. ``source`` is injectable for tests; production builds Spoonacular."""
    q = query.strip()
    if not q:
        return []
    if source is not None:
        return await source.discover(q, limit=settings.recipe_discover_limit)
    if not settings.spoonacular_api_key:
        raise _DISABLED
    async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
        src = SpoonacularSource(client, settings.spoonacular_api_key, settings.spoonacular_base_url)
        return await src.discover(q, limit=settings.recipe_discover_limit)


async def import_recipe(
    db: AsyncSession,
    user_id: uuid.UUID,
    source_id: str,
    *,
    source: RecipeSource | None = None,
) -> RecipeOut:
    """Fetch an external recipe and save it as a Plate recipe (ingredients → foods → items)."""
    if source is not None:
        normalized = await source.fetch(source_id)
    elif settings.spoonacular_api_key:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
            src = SpoonacularSource(
                client, settings.spoonacular_api_key, settings.spoonacular_base_url
            )
            normalized = await src.fetch(source_id)
    else:
        raise _DISABLED

    if normalized is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    if not normalized.ingredients:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Recipe had no ingredients with usable nutrition.",
        )

    items: list[RecipeItem] = []
    for order, ing in enumerate(normalized.ingredients):
        food, quantity, unit = _ingredient_to_food(ing)
        db.add(food)
        items.append(RecipeItem(food=food, quantity=quantity, unit=unit, order=order))

    description = normalized.source_url or normalized.summary
    recipe = Recipe(user_id=user_id, name=normalized.title, description=description)
    recipe.items = items
    db.add(recipe)
    await db.commit()

    from app.services.recipe_service import _reload

    return _to_out(await _reload(db, recipe.id))

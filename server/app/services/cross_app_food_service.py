"""Resolve free-text ingredients to foods and log them — the service behind /cross-app.

Cookbook sends ``{name, quantity, unit}`` rows. Resolution is deliberately conservative:

- Match: case-insensitive substring against the local ``foods`` cache (recipe-import foods
  excluded, like normal search), tightest name first — searching "chicken breast" prefers
  "Chicken Breast" over "Chicken Breast, Rotisserie Seasoned".
- Portion: :func:`~app.nutrition.portions.scale_food` does the math. Cooking units it can't
  ground (cups, tbsp — density depends on the food) mark the item unmatched rather than guess;
  lb/kg are canonicalized to oz/g first since recipes use them constantly. No quantity at all
  ⇒ one serving.

Unmatched items are returned flagged (resolve) or counted as skipped (log). The caller shows
estimates the user can judge — never silently wrong numbers (the Plate photo-logging rule).
"""

import logging
import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food import Food
from app.models.food_log_entry import FoodLogEntry
from app.nutrition.portions import MacroSnapshot, scale_food
from app.schemas.cross_app import (
    CrossAppFoodItem,
    CrossAppLogRequest,
    CrossAppLogResponse,
    ResolvedFoodOut,
    ResolveFoodsRequest,
    ResolveFoodsResponse,
)

log = logging.getLogger(__name__)

_POUND_UNITS = {"lb", "lbs", "pound", "pounds"}
_KILO_UNITS = {"kg", "kgs", "kilogram", "kilograms"}


def _canonical_portion(item: CrossAppFoodItem) -> tuple[float, str]:
    """(quantity, unit) in a form scale_food understands; unquantified rows = 1 serving."""
    unit = (item.unit or "").strip().lower()
    quantity = item.quantity
    if quantity is None:
        return 1.0, "serving"
    if unit in _POUND_UNITS:
        return quantity * 16.0, "oz"
    if unit in _KILO_UNITS:
        return quantity * 1000.0, "g"
    if not unit:
        return quantity, "serving"
    return quantity, unit


async def _best_match(db: AsyncSession, name: str) -> Food | None:
    result = await db.execute(
        select(Food)
        .where(Food.name.ilike(f"%{name}%"), Food.source != "spoonacular")
        .order_by(func.length(Food.name), Food.name)
        .limit(1)
    )
    return result.scalar_one_or_none()


async def _resolve_one(
    db: AsyncSession, item: CrossAppFoodItem
) -> tuple[ResolvedFoodOut, Food | None, MacroSnapshot | None]:
    food = await _best_match(db, item.name)
    if food is None:
        return ResolvedFoodOut(name=item.name, matched=False), None, None
    quantity, unit = _canonical_portion(item)
    try:
        snap = scale_food(food, quantity, unit)
    except ValueError:
        return ResolvedFoodOut(name=item.name, matched=False), None, None
    return (
        ResolvedFoodOut(
            name=item.name,
            matched=True,
            food_id=food.id,
            food_name=food.name,
            kcal=snap.kcal,
            protein_g=snap.protein_g,
            carbs_g=snap.carbs_g,
            fat_g=snap.fat_g,
        ),
        food,
        snap,
    )


async def resolve_foods(db: AsyncSession, req: ResolveFoodsRequest) -> ResolveFoodsResponse:
    items = [(await _resolve_one(db, item))[0] for item in req.items]
    return ResolveFoodsResponse(items=items)


async def log_cross_app_recipe(
    db: AsyncSession, user_id: uuid.UUID, req: CrossAppLogRequest
) -> CrossAppLogResponse:
    """One snapshotted diary entry per resolvable item (the recipe-log history rule)."""
    logged = 0
    skipped = 0
    for item in req.items:
        resolved, food, snap = await _resolve_one(db, item)
        if food is None or snap is None:
            skipped += 1
            continue
        quantity, unit = _canonical_portion(item)
        db.add(
            FoodLogEntry(
                user_id=user_id,
                food_id=food.id,
                date=req.date,
                meal=req.meal,
                quantity=quantity,
                unit=unit,
                kcal=snap.kcal,
                protein_g=snap.protein_g,
                carbs_g=snap.carbs_g,
                fat_g=snap.fat_g,
                fiber_g=snap.fiber_g,
                sugar_g=snap.sugar_g,
                sat_fat_g=snap.sat_fat_g,
                cholesterol_mg=snap.cholesterol_mg,
                sodium_mg=snap.sodium_mg,
            )
        )
        logged += 1
    if logged:
        await db.commit()
    if skipped:
        log.info(
            "cross-app log (%s): %d logged, %d skipped",
            req.recipe_name or "recipe",
            logged,
            skipped,
        )
    return CrossAppLogResponse(logged=logged, skipped=skipped)

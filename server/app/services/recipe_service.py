"""Saved meals / recipes (CLAUDE.md §4, Phase 8).

A recipe is a **named, ordered set of foods** — a live template. Its totals are computed from the
current source foods on read; nothing is snapshotted until it's *logged*, at which point each item
becomes a denormalized :class:`~app.models.food_log_entry.FoodLogEntry` (the same history rule as
manual logging). Items whose source food was deleted (``food_id`` SET NULL) are skipped in totals
and when logging. Mirrors Spotter's program service (parent-flush-then-children, replace-children).
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food_log_entry import FoodLogEntry
from app.models.recipe import Recipe
from app.models.recipe_item import RecipeItem
from app.nutrition.portions import MacroSnapshot, scale_food
from app.nutrition.totals import sum_entries
from app.schemas.log import LogEntryOut, TotalsOut
from app.schemas.recipe import (
    RecipeCreate,
    RecipeItemIn,
    RecipeItemOut,
    RecipeOut,
    RecipeUpdate,
)


async def _load_owned_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> Recipe:
    recipe = await db.get(Recipe, recipe_id)
    if recipe is None or recipe.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    return recipe


def _item_snapshot(item: RecipeItem) -> MacroSnapshot | None:
    """Scale a single item from its current source food, or None if the food is gone/unscalable."""
    if item.food is None:
        return None
    try:
        return scale_food(item.food, item.quantity, item.unit)
    except ValueError:
        return None


def _to_out(recipe: Recipe) -> RecipeOut:
    item_outs: list[RecipeItemOut] = []
    snapshots: list[MacroSnapshot] = []
    for item in recipe.items:
        snap = _item_snapshot(item)
        if snap is not None:
            snapshots.append(snap)
        item_outs.append(
            RecipeItemOut(
                id=item.id,
                food_id=item.food_id,
                food_name=item.food.name if item.food is not None else None,
                quantity=item.quantity,
                unit=item.unit,
                order=item.order,
                kcal=None if snap is None else snap.kcal,
                protein_g=None if snap is None else snap.protein_g,
                carbs_g=None if snap is None else snap.carbs_g,
                fat_g=None if snap is None else snap.fat_g,
            )
        )
    t = sum_entries(snapshots)
    totals = TotalsOut(
        kcal=t.kcal,
        protein_g=t.protein_g,
        carbs_g=t.carbs_g,
        fat_g=t.fat_g,
        fiber_g=t.fiber_g,
        sugar_g=t.sugar_g,
        sat_fat_g=t.sat_fat_g,
        cholesterol_mg=t.cholesterol_mg,
        sodium_mg=t.sodium_mg,
    )
    return RecipeOut(
        id=recipe.id,
        name=recipe.name,
        description=recipe.description,
        items=item_outs,
        totals=totals,
    )


async def _reload(db: AsyncSession, recipe_id: uuid.UUID) -> Recipe:
    """Re-fetch with items + foods eagerly loaded (both relationships are selectin)."""
    result = await db.execute(select(Recipe).where(Recipe.id == recipe_id))
    return result.scalar_one()


def _build_items(req_items: list[RecipeItemIn]) -> list[RecipeItem]:
    return [
        RecipeItem(food_id=i.food_id, quantity=i.quantity, unit=i.unit, order=order)
        for order, i in enumerate(req_items)
    ]


async def create_recipe(db: AsyncSession, user_id: uuid.UUID, req: RecipeCreate) -> RecipeOut:
    recipe = Recipe(user_id=user_id, name=req.name, description=req.description)
    recipe.items = _build_items(req.items)
    db.add(recipe)
    await db.commit()
    return _to_out(await _reload(db, recipe.id))


async def list_recipes(db: AsyncSession, user_id: uuid.UUID) -> list[RecipeOut]:
    result = await db.execute(
        select(Recipe).where(Recipe.user_id == user_id).order_by(Recipe.created_at)
    )
    return [_to_out(r) for r in result.scalars().all()]


async def get_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> RecipeOut:
    recipe = await _load_owned_recipe(db, user_id, recipe_id)
    return _to_out(recipe)


async def update_recipe(
    db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID, req: RecipeUpdate
) -> RecipeOut:
    recipe = await _load_owned_recipe(db, user_id, recipe_id)
    if req.name is not None:
        recipe.name = req.name
    if req.description is not None:
        recipe.description = req.description
    await db.commit()
    return _to_out(await _reload(db, recipe.id))


async def replace_items(
    db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID, items: list[RecipeItemIn]
) -> RecipeOut:
    recipe = await _load_owned_recipe(db, user_id, recipe_id)
    recipe.items = _build_items(items)  # delete-orphan clears the old rows
    await db.commit()
    return _to_out(await _reload(db, recipe.id))


async def delete_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> None:
    recipe = await _load_owned_recipe(db, user_id, recipe_id)
    await db.delete(recipe)
    await db.commit()


async def export_recipes(db: AsyncSession, user_id: uuid.UUID) -> list["RecipeExport"]:
    """All of a user's recipes as name+amount rows, for Cookbook's one-time migration.

    Items whose source food was deleted (``food_id`` SET NULL) are skipped — there is no name
    left to export. Recipes that end up empty are still exported (name/description carry over).
    """
    from app.schemas.recipe import RecipeExport, RecipeExportItem

    result = await db.execute(
        select(Recipe).where(Recipe.user_id == user_id).order_by(Recipe.created_at)
    )
    exports: list[RecipeExport] = []
    for recipe in result.scalars().all():
        items = [
            RecipeExportItem(food_name=item.food.name, quantity=item.quantity, unit=item.unit)
            for item in recipe.items
            if item.food is not None
        ]
        exports.append(
            RecipeExport(
                id=recipe.id,
                name=recipe.name,
                description=recipe.description,
                items=items,
            )
        )
    return exports


async def log_recipe(
    db: AsyncSession,
    user_id: uuid.UUID,
    recipe_id: uuid.UUID,
    day: datetime.date,
    meal: str,
) -> list[LogEntryOut]:
    """Expand a recipe into the day's log: one snapshotted ``FoodLogEntry`` per scalable item."""
    recipe = await _load_owned_recipe(db, user_id, recipe_id)
    created: list[FoodLogEntry] = []
    for item in recipe.items:
        snap = _item_snapshot(item)
        if snap is None:
            continue
        entry = FoodLogEntry(
            user_id=user_id,
            food_id=item.food_id,
            date=day,
            meal=meal,
            quantity=item.quantity,
            unit=item.unit,
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
        db.add(entry)
        created.append(entry)
    if not created:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Recipe has no loggable items (its foods may have been deleted).",
        )
    await db.commit()
    # Resolve food names from a single pass over the recipe's items rather than scanning the list
    # per created entry (was O(n²) for large recipes).
    food_names = {
        item.food_id: item.food.name
        for item in recipe.items
        if item.food_id is not None and item.food is not None
    }
    out: list[LogEntryOut] = []
    for entry in created:
        await db.refresh(entry)
        o = LogEntryOut.model_validate(entry)
        o.food_name = food_names.get(entry.food_id) if entry.food_id is not None else None
        out.append(o)
    return out

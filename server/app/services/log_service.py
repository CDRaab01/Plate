"""Food-log CRUD + daily aggregation (CLAUDE.md §4, §6).

Every entry carries a **denormalized macro snapshot** taken at log time, so later edits to (or
deletion of) the source food never rewrite history. Quantity/unit edits re-snapshot from the
source food when it still exists; if the food was deleted, macros are scaled proportionally from
the stored snapshot so the entry stays internally consistent.
"""
import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food import Food
from app.models.food_log_entry import FoodLogEntry
from app.nutrition.constants import STATIC_DAILY_TARGET
from app.nutrition.portions import MacroSnapshot, scale_food
from app.nutrition.totals import sum_entries
from app.services.goal_service import compute_targets_for
from app.schemas.log import (
    DailyLog,
    LogEntryCreate,
    LogEntryOut,
    LogEntryUpdate,
    MealGroup,
    TotalsOut,
    MEALS,
)


def _apply_snapshot(entry: FoodLogEntry, snap: MacroSnapshot) -> None:
    entry.kcal = snap.kcal
    entry.protein_g = snap.protein_g
    entry.carbs_g = snap.carbs_g
    entry.fat_g = snap.fat_g
    entry.fiber_g = snap.fiber_g
    entry.sugar_g = snap.sugar_g
    entry.sat_fat_g = snap.sat_fat_g
    entry.cholesterol_mg = snap.cholesterol_mg
    entry.sodium_mg = snap.sodium_mg


async def _load_owned_entry(
    db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID
) -> FoodLogEntry:
    entry = await db.get(FoodLogEntry, entry_id)
    if entry is None or entry.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log entry not found")
    return entry


def _to_out(entry: FoodLogEntry, food_name: str | None) -> LogEntryOut:
    out = LogEntryOut.model_validate(entry)
    out.food_name = food_name
    return out


async def create_entry(
    db: AsyncSession, user_id: uuid.UUID, req: LogEntryCreate
) -> LogEntryOut:
    food = await db.get(Food, req.food_id)
    if food is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Food not found")
    try:
        snap = scale_food(food, req.quantity, req.unit)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    entry = FoodLogEntry(
        user_id=user_id,
        food_id=food.id,
        date=req.date,
        meal=req.meal,
        quantity=req.quantity,
        unit=req.unit,
    )
    _apply_snapshot(entry, snap)
    db.add(entry)
    await db.commit()
    await db.refresh(entry)
    return _to_out(entry, food.name)


async def update_entry(
    db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID, req: LogEntryUpdate
) -> LogEntryOut:
    entry = await _load_owned_entry(db, user_id, entry_id)

    if req.date is not None:
        entry.date = req.date
    if req.meal is not None:
        entry.meal = req.meal

    new_quantity = req.quantity if req.quantity is not None else entry.quantity
    new_unit = req.unit if req.unit is not None else entry.unit
    portion_changed = req.quantity is not None or req.unit is not None

    food_name: str | None = None
    if entry.food_id is not None:
        food = await db.get(Food, entry.food_id)
        food_name = food.name if food else None
        if portion_changed and food is not None:
            try:
                snap = scale_food(food, new_quantity, new_unit)
            except ValueError as exc:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)
                ) from exc
            entry.quantity = new_quantity
            entry.unit = new_unit
            _apply_snapshot(entry, snap)
            await db.commit()
            await db.refresh(entry)
            return _to_out(entry, food_name)

    if portion_changed:
        # Source food is gone (SET NULL) or unit changed without a source: scale the existing
        # snapshot proportionally, which only makes sense when the unit is unchanged.
        if new_unit != entry.unit:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Cannot change unit for an entry whose source food was deleted",
            )
        ratio = new_quantity / entry.quantity if entry.quantity else 1.0
        entry.quantity = new_quantity
        _apply_snapshot(
            entry,
            MacroSnapshot(
                kcal=entry.kcal * ratio,
                protein_g=entry.protein_g * ratio,
                carbs_g=entry.carbs_g * ratio,
                fat_g=entry.fat_g * ratio,
                fiber_g=None if entry.fiber_g is None else entry.fiber_g * ratio,
                sugar_g=None if entry.sugar_g is None else entry.sugar_g * ratio,
                sat_fat_g=None if entry.sat_fat_g is None else entry.sat_fat_g * ratio,
                cholesterol_mg=None
                if entry.cholesterol_mg is None
                else entry.cholesterol_mg * ratio,
                sodium_mg=None if entry.sodium_mg is None else entry.sodium_mg * ratio,
            ),
        )

    await db.commit()
    await db.refresh(entry)
    return _to_out(entry, food_name)


async def delete_entry(db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID) -> None:
    entry = await _load_owned_entry(db, user_id, entry_id)
    await db.delete(entry)
    await db.commit()


def _totals_out(entries) -> TotalsOut:
    t = sum_entries(entries)
    return TotalsOut(
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


async def get_day(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date, *, trained: bool = False
) -> DailyLog:
    result = await db.execute(
        select(FoodLogEntry)
        .where(FoodLogEntry.user_id == user_id, FoodLogEntry.date == day)
        .order_by(FoodLogEntry.created_at)
    )
    entries = list(result.scalars().all())

    # Resolve source-food names in one batched query (entries whose food was deleted show None).
    food_ids = {e.food_id for e in entries if e.food_id is not None}
    names: dict[uuid.UUID, str] = {}
    if food_ids:
        rows = await db.execute(select(Food.id, Food.name).where(Food.id.in_(food_ids)))
        names = {fid: name for fid, name in rows.all()}

    meals: list[MealGroup] = []
    for meal in MEALS:
        meal_entries = [e for e in entries if e.meal == meal]
        meals.append(
            MealGroup(
                meal=meal,
                entries=[_to_out(e, names.get(e.food_id)) for e in meal_entries],
                totals=_totals_out(meal_entries),
            )
        )

    targets = await _targets_out(db, user_id, day, trained=trained)
    return DailyLog(
        date=day,
        meals=meals,
        totals=_totals_out(entries),
        targets=targets,
        trained_today=trained,
    )


async def _targets_out(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date, *, trained: bool = False
) -> TotalsOut:
    """The day's macro targets: computed from the user's goal, or the static placeholder if none.

    The Phase 3 engine sets only the four primary macros; secondary nutrients have no target, so
    they surface as 0 (the client shows totals-only for those). ``trained`` adds the training-day
    bump (Spotter-awareness, §7) when the user worked out that day.
    """
    computed = await compute_targets_for(db, user_id, day, trained=trained)
    if computed is not None:
        return TotalsOut(
            kcal=computed.kcal,
            protein_g=computed.protein_g,
            carbs_g=computed.carbs_g,
            fat_g=computed.fat_g,
            fiber_g=0.0,
            sugar_g=0.0,
            sat_fat_g=0.0,
            cholesterol_mg=0.0,
            sodium_mg=0.0,
        )
    return TotalsOut(
        kcal=STATIC_DAILY_TARGET.kcal,
        protein_g=STATIC_DAILY_TARGET.protein_g,
        carbs_g=STATIC_DAILY_TARGET.carbs_g,
        fat_g=STATIC_DAILY_TARGET.fat_g,
        fiber_g=STATIC_DAILY_TARGET.fiber_g,
        sugar_g=STATIC_DAILY_TARGET.sugar_g,
        sat_fat_g=STATIC_DAILY_TARGET.sat_fat_g,
        cholesterol_mg=STATIC_DAILY_TARGET.cholesterol_mg,
        sodium_mg=STATIC_DAILY_TARGET.sodium_mg,
    )

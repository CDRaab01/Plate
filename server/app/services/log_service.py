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
from app.schemas.food import FoodOut
from app.schemas.log import (
    DailyLog,
    DaySummary,
    LogEntryCreate,
    LogEntryOut,
    LogEntryUpdate,
    MealGroup,
    QuickAddCreate,
    RangeSummaryOut,
    RecentFoodOut,
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
    # Source-food name when there is one; otherwise the entry's own label (quick-add entries).
    out.food_name = food_name if food_name is not None else entry.name
    return out


async def get_recent_foods(
    db: AsyncSession, user_id: uuid.UUID, limit: int = 20
) -> list[RecentFoodOut]:
    """Distinct foods the user has logged, most-recently-logged first, each with the last portion
    used so re-logging a staple is one tap. Deduped in Python over a recent window (quick-adds,
    which have no source food, are excluded — there's nothing to re-log by id)."""
    result = await db.execute(
        select(FoodLogEntry)
        .where(FoodLogEntry.user_id == user_id, FoodLogEntry.food_id.is_not(None))
        .order_by(FoodLogEntry.created_at.desc())
        .limit(200)
    )
    latest_per_food: dict[uuid.UUID, FoodLogEntry] = {}
    for entry in result.scalars():
        if entry.food_id not in latest_per_food:
            latest_per_food[entry.food_id] = entry
        if len(latest_per_food) >= limit:
            break
    if not latest_per_food:
        return []
    foods = {
        food.id: food
        for food in (
            await db.execute(select(Food).where(Food.id.in_(latest_per_food.keys())))
        ).scalars()
    }
    out: list[RecentFoodOut] = []
    for food_id, entry in latest_per_food.items():
        food = foods.get(food_id)
        if food is None:
            continue  # source food was deleted — nothing to re-log
        out.append(
            RecentFoodOut(
                food=FoodOut.model_validate(food),
                last_meal=entry.meal,
                last_quantity=entry.quantity,
                last_unit=entry.unit,
            )
        )
    return out


async def copy_day(
    db: AsyncSession,
    user_id: uuid.UUID,
    from_date: datetime.date,
    to_date: datetime.date,
) -> list[LogEntryOut]:
    """Copy every entry from ``from_date`` into ``to_date`` (the 'copy yesterday' quick-log).

    The denormalized macro snapshot is copied verbatim — same food, quantity, and unit, so no
    re-scaling is needed and history stays intact even if the source food later changes. This is
    additive (it does not clear the target day), and ``source_ref`` is dropped since a copy isn't
    cross-app-sourced.
    """
    result = await db.execute(
        select(FoodLogEntry)
        .where(FoodLogEntry.user_id == user_id, FoodLogEntry.date == from_date)
        .order_by(FoodLogEntry.created_at)
    )
    copies: list[FoodLogEntry] = []
    for e in result.scalars():
        copy = FoodLogEntry(
            user_id=user_id,
            food_id=e.food_id,
            name=e.name,
            date=to_date,
            meal=e.meal,
            quantity=e.quantity,
            unit=e.unit,
            kcal=e.kcal,
            protein_g=e.protein_g,
            carbs_g=e.carbs_g,
            fat_g=e.fat_g,
            fiber_g=e.fiber_g,
            sugar_g=e.sugar_g,
            sat_fat_g=e.sat_fat_g,
            cholesterol_mg=e.cholesterol_mg,
            sodium_mg=e.sodium_mg,
        )
        db.add(copy)
        copies.append(copy)
    if copies:
        await db.commit()
        for copy in copies:
            await db.refresh(copy)

    food_ids = {c.food_id for c in copies if c.food_id is not None}
    names: dict[uuid.UUID, str] = {}
    if food_ids:
        rows = await db.execute(select(Food.id, Food.name).where(Food.id.in_(food_ids)))
        names = {fid: name for fid, name in rows.all()}
    return [_to_out(c, names.get(c.food_id)) for c in copies]


async def create_entry(db: AsyncSession, user_id: uuid.UUID, req: LogEntryCreate) -> LogEntryOut:
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


async def create_quick_add(
    db: AsyncSession, user_id: uuid.UUID, req: QuickAddCreate
) -> LogEntryOut:
    """Log raw macros with no source food (MyFitnessPal-style quick add).

    Stored with ``food_id = None`` and a label (``name``), quantity 1 serving, macros taken
    directly from the request — there's nothing to scale.
    """
    entry = FoodLogEntry(
        user_id=user_id,
        food_id=None,
        name=(req.name or "").strip() or "Quick add",
        date=req.date,
        meal=req.meal,
        quantity=1.0,
        unit="serving",
    )
    _apply_snapshot(
        entry,
        MacroSnapshot(
            kcal=req.kcal,
            protein_g=req.protein_g,
            carbs_g=req.carbs_g,
            fat_g=req.fat_g,
            fiber_g=req.fiber_g,
            sugar_g=req.sugar_g,
            sat_fat_g=req.sat_fat_g,
            cholesterol_mg=req.cholesterol_mg,
            sodium_mg=req.sodium_mg,
        ),
    )
    db.add(entry)
    await db.commit()
    await db.refresh(entry)
    return _to_out(entry, None)


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


def _avg(total: TotalsOut, n: int) -> TotalsOut:
    d = float(n) if n else 1.0
    return TotalsOut(
        kcal=total.kcal / d,
        protein_g=total.protein_g / d,
        carbs_g=total.carbs_g / d,
        fat_g=total.fat_g / d,
        fiber_g=total.fiber_g / d,
        sugar_g=total.sugar_g / d,
        sat_fat_g=total.sat_fat_g / d,
        cholesterol_mg=total.cholesterol_mg / d,
        sodium_mg=total.sodium_mg / d,
    )


async def get_summary(
    db: AsyncSession,
    user_id: uuid.UUID,
    start: datetime.date,
    end: datetime.date,
    *,
    training_days: set[datetime.date] | None = None,
) -> RangeSummaryOut:
    """Per-day totals across ``[start, end]`` plus period total and daily averages (Phase 8).

    One row per calendar day in the range (zero-filled when nothing was logged). ``training_days``
    carries the Spotter-awareness flag per date so each day's ``target_kcal`` includes the
    training-day bump where applicable.
    """
    trained_on = training_days or set()
    result = await db.execute(
        select(FoodLogEntry).where(
            FoodLogEntry.user_id == user_id,
            FoodLogEntry.date >= start,
            FoodLogEntry.date <= end,
        )
    )
    entries = list(result.scalars().all())
    by_date: dict[datetime.date, list[FoodLogEntry]] = {}
    for e in entries:
        by_date.setdefault(e.date, []).append(e)

    days: list[DaySummary] = []
    num_days = (end - start).days + 1
    for offset in range(num_days):
        day = start + datetime.timedelta(days=offset)
        trained = day in trained_on
        day_targets = await _targets_out(db, user_id, day, trained=trained)
        days.append(
            DaySummary(
                date=day,
                totals=_totals_out(by_date.get(day, [])),
                target_kcal=day_targets.kcal,
                trained=trained,
            )
        )

    total = _totals_out(entries)
    return RangeSummaryOut(
        start=start,
        end=end,
        days=days,
        total=total,
        averages=_avg(total, num_days),
    )


async def remaining_macros(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date, *, trained: bool = False
) -> dict | None:
    """Macros left today = targets − consumed (federated awareness Link F — Cookbook ranks recipes
    that fit). None when the user has no active goal (no personalized targets to subtract against),
    so the consumer can degrade to absence. Rounded ints, clamped at 0 (never "negative kcal left").
    """
    targets = await compute_targets_for(db, user_id, day, trained=trained)
    if targets is None:
        return None
    result = await db.execute(
        select(FoodLogEntry).where(FoodLogEntry.user_id == user_id, FoodLogEntry.date == day)
    )
    consumed = sum_entries(list(result.scalars().all()))
    return {
        "kcal_remaining": max(0, round(targets.kcal - consumed.kcal)),
        "protein_g_remaining": max(0, round(targets.protein_g - consumed.protein_g)),
        "carbs_g_remaining": max(0, round(targets.carbs_g - consumed.carbs_g)),
        "fat_g_remaining": max(0, round(targets.fat_g - consumed.fat_g)),
    }

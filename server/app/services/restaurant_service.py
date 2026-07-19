"""Restaurant/chain checkbox templates ("I ate a Salsa Grille bowl").

A restaurant is a **live template** like a recipe, but its children are categorized
build-your-own components ("Barbacoa" under "Protein") the user ticks to log a composed meal —
one snapshotted :class:`~app.models.food_log_entry.FoodLogEntry` per ticked component, the
``log_recipe`` precedent. Two twists over recipes:

* **Sharing** — restaurants default ``shared``: a chain's menu is objectively shared data, so
  every account on this server can see and log from it (entries land under the *caller*), while
  patch/replace/delete stay owner-only.
* **Official foods** — a component submitted with an inline ``macros`` block (the chain's
  published nutrition) mints a ``Food(source="user", brand=<restaurant name>)`` and links it,
  mirroring recipe import's ``_ingredient_to_food``.
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food import Food
from app.models.food_log_entry import FoodLogEntry
from app.models.restaurant import Restaurant
from app.models.restaurant_component import RestaurantComponent
from app.nutrition.portions import MacroSnapshot, scale_food
from app.schemas.log import LogEntryOut
from app.schemas.restaurant import (
    ComponentMacrosIn,
    RestaurantComponentIn,
    RestaurantComponentOut,
    RestaurantCreate,
    RestaurantLogRequest,
    RestaurantOut,
    RestaurantUpdate,
)

_NOT_FOUND = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Restaurant not found")
_NO_COMPONENTS = HTTPException(
    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
    detail="Add at least one item before saving the restaurant.",
)


def _scaled(value: float | None, factor: float) -> float | None:
    return None if value is None else value * factor


async def _load_owned_restaurant(
    db: AsyncSession, user_id: uuid.UUID, restaurant_id: uuid.UUID
) -> Restaurant:
    """Owner-only access (patch/replace/delete): another account's restaurant is a 404."""
    restaurant = await db.get(Restaurant, restaurant_id)
    if restaurant is None or restaurant.user_id != user_id:
        raise _NOT_FOUND
    return restaurant


async def _load_visible_restaurant(
    db: AsyncSession, user_id: uuid.UUID, restaurant_id: uuid.UUID
) -> Restaurant:
    """Read/log access: the owner's restaurants plus anyone's shared ones."""
    restaurant = await db.get(Restaurant, restaurant_id)
    if restaurant is None or (restaurant.user_id != user_id and not restaurant.shared):
        raise _NOT_FOUND
    return restaurant


def _component_snapshot(
    component: RestaurantComponent, quantity: float | None = None
) -> MacroSnapshot | None:
    """Scale a component from its current linked food, or None if unlinked/unscalable."""
    if component.food is None:
        return None
    try:
        return scale_food(component.food, quantity or component.quantity, component.unit)
    except ValueError:
        return None


def _to_out(restaurant: Restaurant, user_id: uuid.UUID) -> RestaurantOut:
    # `default_checked` is the owner's "my usual order" pre-tick config. On a *shared* restaurant it
    # must stay private to the owner — otherwise one account's pre-ticks show up pre-checked on
    # everyone else's log sheet (the components table is shared, so the flag is inherently global).
    # Non-owners always get a clean sheet (all False) and tick their own.
    is_owner = restaurant.user_id == user_id
    component_outs: list[RestaurantComponentOut] = []
    for comp in restaurant.components:
        snap = _component_snapshot(comp)
        component_outs.append(
            RestaurantComponentOut(
                id=comp.id,
                category=comp.category,
                name=comp.name,
                food_id=comp.food_id,
                food_name=comp.food.name if comp.food is not None else None,
                quantity=comp.quantity,
                unit=comp.unit,
                order=comp.order,
                default_checked=comp.default_checked if is_owner else False,
                kcal=None if snap is None else snap.kcal,
                protein_g=None if snap is None else snap.protein_g,
                carbs_g=None if snap is None else snap.carbs_g,
                fat_g=None if snap is None else snap.fat_g,
            )
        )
    return RestaurantOut(
        id=restaurant.id,
        name=restaurant.name,
        menu_url=restaurant.menu_url,
        notes=restaurant.notes,
        shared=restaurant.shared,
        is_owner=is_owner,
        components=component_outs,
    )


async def _reload(db: AsyncSession, restaurant_id: uuid.UUID) -> Restaurant:
    """Re-fetch with components + foods eagerly loaded (both relationships are selectin)."""
    result = await db.execute(select(Restaurant).where(Restaurant.id == restaurant_id))
    return result.scalar_one()


def _mint_official_food(name: str, brand: str, macros: ComponentMacrosIn) -> Food:
    """Build a Food carrying the chain's published per-serving numbers (recipe-import precedent).

    With a known serving weight we derive a real per-100g basis (gram logging works); without one
    the per-100g columns are filled with the per-serving values to satisfy non-null — such a food
    is always logged by serving.
    """
    per_serving = dict(
        kcal_per_serving=macros.kcal,
        protein_g_per_serving=macros.protein_g,
        carbs_g_per_serving=macros.carbs_g,
        fat_g_per_serving=macros.fat_g,
        fiber_g_per_serving=macros.fiber_g,
        sugar_g_per_serving=macros.sugar_g,
        sat_fat_g_per_serving=macros.sat_fat_g,
        cholesterol_mg_per_serving=macros.cholesterol_mg,
        sodium_mg_per_serving=macros.sodium_mg,
    )
    if macros.serving_grams and macros.serving_grams > 0:
        f = 100.0 / macros.serving_grams
        return Food(
            source="user",
            name=name,
            brand=brand,
            serving_size=macros.serving_grams,
            serving_unit=(macros.serving_desc or "serving")[:32],
            kcal_per_100g=macros.kcal * f,
            protein_g_per_100g=macros.protein_g * f,
            carbs_g_per_100g=macros.carbs_g * f,
            fat_g_per_100g=macros.fat_g * f,
            fiber_g_per_100g=_scaled(macros.fiber_g, f),
            sugar_g_per_100g=_scaled(macros.sugar_g, f),
            sat_fat_g_per_100g=_scaled(macros.sat_fat_g, f),
            cholesterol_mg_per_100g=_scaled(macros.cholesterol_mg, f),
            sodium_mg_per_100g=_scaled(macros.sodium_mg, f),
            **per_serving,
        )
    return Food(
        source="user",
        name=name,
        brand=brand,
        serving_unit=(macros.serving_desc or "serving")[:32],
        kcal_per_100g=macros.kcal,
        protein_g_per_100g=macros.protein_g,
        carbs_g_per_100g=macros.carbs_g,
        fat_g_per_100g=macros.fat_g,
        fiber_g_per_100g=macros.fiber_g,
        sugar_g_per_100g=macros.sugar_g,
        sat_fat_g_per_100g=macros.sat_fat_g,
        cholesterol_mg_per_100g=macros.cholesterol_mg,
        sodium_mg_per_100g=macros.sodium_mg,
        **per_serving,
    )


def _build_components(
    restaurant_name: str, req_components: list[RestaurantComponentIn]
) -> list[RestaurantComponent]:
    """Component rows in request order; an inline ``macros`` block mints its official food."""
    components: list[RestaurantComponent] = []
    for order, c in enumerate(req_components):
        component = RestaurantComponent(
            category=c.category,
            name=c.name,
            food_id=c.food_id,
            quantity=c.quantity,
            unit=c.unit,
            order=order,
            default_checked=c.default_checked,
        )
        if c.macros is not None:
            component.food = _mint_official_food(c.name, restaurant_name, c.macros)
        components.append(component)
    return components


async def create_restaurant(
    db: AsyncSession, user_id: uuid.UUID, req: RestaurantCreate
) -> RestaurantOut:
    # A componentless restaurant is a dead-end card (nothing to log). Require one item, mirroring
    # the recipe editor's "add at least one ingredient" rule.
    if not req.components:
        raise _NO_COMPONENTS
    restaurant = Restaurant(
        user_id=user_id,
        name=req.name,
        menu_url=req.menu_url,
        notes=req.notes,
        shared=req.shared,
    )
    restaurant.components = _build_components(req.name, req.components)
    db.add(restaurant)
    await db.commit()
    return _to_out(await _reload(db, restaurant.id), user_id)


async def list_restaurants(db: AsyncSession, user_id: uuid.UUID) -> list[RestaurantOut]:
    """The caller's own restaurants first, then other accounts' shared ones."""
    result = await db.execute(
        select(Restaurant)
        .where(or_(Restaurant.user_id == user_id, Restaurant.shared.is_(True)))
        .order_by(Restaurant.created_at)
    )
    restaurants = list(result.scalars().all())
    restaurants.sort(key=lambda r: r.user_id != user_id)  # stable: own first, created order kept
    return [_to_out(r, user_id) for r in restaurants]


async def get_restaurant(
    db: AsyncSession, user_id: uuid.UUID, restaurant_id: uuid.UUID
) -> RestaurantOut:
    restaurant = await _load_visible_restaurant(db, user_id, restaurant_id)
    return _to_out(restaurant, user_id)


async def update_restaurant(
    db: AsyncSession, user_id: uuid.UUID, restaurant_id: uuid.UUID, req: RestaurantUpdate
) -> RestaurantOut:
    restaurant = await _load_owned_restaurant(db, user_id, restaurant_id)
    if req.name is not None:
        restaurant.name = req.name
    if req.menu_url is not None:
        restaurant.menu_url = req.menu_url
    if req.notes is not None:
        restaurant.notes = req.notes
    if req.shared is not None:
        restaurant.shared = req.shared
    await db.commit()
    return _to_out(await _reload(db, restaurant.id), user_id)


async def replace_components(
    db: AsyncSession,
    user_id: uuid.UUID,
    restaurant_id: uuid.UUID,
    components: list[RestaurantComponentIn],
) -> RestaurantOut:
    restaurant = await _load_owned_restaurant(db, user_id, restaurant_id)  # ownership before shape
    if not components:
        raise _NO_COMPONENTS  # editing down to zero would strand an unloggable restaurant
    restaurant.components = _build_components(restaurant.name, components)  # delete-orphan clears
    await db.commit()
    return _to_out(await _reload(db, restaurant.id), user_id)


async def delete_restaurant(db: AsyncSession, user_id: uuid.UUID, restaurant_id: uuid.UUID) -> None:
    restaurant = await _load_owned_restaurant(db, user_id, restaurant_id)
    await db.delete(restaurant)
    await db.commit()


async def log_restaurant(
    db: AsyncSession,
    user_id: uuid.UUID,
    restaurant_id: uuid.UUID,
    req: RestaurantLogRequest,
) -> list[LogEntryOut]:
    """Snapshot the ticked components into the caller's day: one entry per loggable selection.

    Selections are validated against the restaurant's own component map — a component id from a
    different restaurant is a 400, so ids can't be injected across restaurants (or users).
    Entries always land under the **caller's** user_id, even on someone else's shared restaurant.
    """
    restaurant = await _load_visible_restaurant(db, user_id, restaurant_id)
    by_id = {comp.id: comp for comp in restaurant.components}

    created: list[FoodLogEntry] = []
    for selection in req.selections:
        component = by_id.get(selection.component_id)
        if component is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Selection references a component not in this restaurant.",
            )
        quantity = selection.quantity or component.quantity
        snap = _component_snapshot(component, quantity)
        if snap is None:  # unlinked (food deleted / never linked): skip, like recipe items
            continue
        entry = FoodLogEntry(
            user_id=user_id,
            food_id=component.food_id,
            date=req.date,
            meal=req.meal,
            quantity=quantity,
            unit=component.unit,
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
            detail="No loggable components selected (link foods to them first).",
        )
    await db.commit()

    food_names = {
        comp.food_id: comp.food.name
        for comp in restaurant.components
        if comp.food_id is not None and comp.food is not None
    }
    out: list[LogEntryOut] = []
    for entry in created:
        await db.refresh(entry)
        o = LogEntryOut.model_validate(entry)
        o.food_name = food_names.get(entry.food_id) if entry.food_id is not None else None
        out.append(o)
    return out

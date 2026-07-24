"""Portion scaling: a food + a logged quantity/unit → a macro snapshot.

Pure functions, unit-tested with table cases. Supported units:

* **grams** (``g``) — scale from the per-100g basis.
* **ounces** (``oz``) — converted to grams (canonical) and scaled from the per-100g basis, so a
  user on imperial units can log "4 oz" directly. Macros are unit-independent — only the gram
  factor changes.
* **servings** (``serving``) — use the per-serving basis when the food defines one; otherwise fall
  back to the food's ``serving_size`` in grams so a serving is still loggable.

Secondary nutrients are optional: a ``None`` on the source stays ``None`` in the snapshot rather
than being silently treated as zero.
"""

from dataclasses import dataclass
from typing import Protocol

from app.nutrition.units import oz_to_g

GRAM_UNITS = {"g", "gram", "grams"}
OUNCE_UNITS = {"oz", "ounce", "ounces"}
SERVING_UNITS = {"serving", "servings"}

# Primary macros are always present on a food; secondaries may be missing.
_PRIMARY_100G = ("kcal", "protein_g", "carbs_g", "fat_g")
_SECONDARY_100G = ("fiber_g", "sugar_g", "sat_fat_g", "cholesterol_mg", "sodium_mg")


class FoodLike(Protocol):
    """The per-100g / per-serving attributes portion scaling reads (duck-typed).

    Both :class:`~app.models.food.Food` and
    :class:`~app.foods.normalize.NormalizedFood` satisfy this.
    """

    kcal_per_100g: float
    protein_g_per_100g: float
    carbs_g_per_100g: float
    fat_g_per_100g: float
    serving_size: float | None


@dataclass(frozen=True)
class MacroSnapshot:
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float | None = None
    sugar_g: float | None = None
    sat_fat_g: float | None = None
    cholesterol_mg: float | None = None
    sodium_mg: float | None = None


def _scaled(value: float | None, factor: float) -> float | None:
    return None if value is None else value * factor


def _from_basis(food, suffix: str, factor: float) -> MacroSnapshot:
    """Build a snapshot from one basis (``_per_100g`` or ``_per_serving``) scaled by ``factor``."""
    return MacroSnapshot(
        kcal=getattr(food, f"kcal{suffix}") * factor,
        protein_g=getattr(food, f"protein_g{suffix}") * factor,
        carbs_g=getattr(food, f"carbs_g{suffix}") * factor,
        fat_g=getattr(food, f"fat_g{suffix}") * factor,
        fiber_g=_scaled(getattr(food, f"fiber_g{suffix}"), factor),
        sugar_g=_scaled(getattr(food, f"sugar_g{suffix}"), factor),
        sat_fat_g=_scaled(getattr(food, f"sat_fat_g{suffix}"), factor),
        cholesterol_mg=_scaled(getattr(food, f"cholesterol_mg{suffix}"), factor),
        sodium_mg=_scaled(getattr(food, f"sodium_mg{suffix}"), factor),
    )


def portion_grams(quantity: float, gram_weight: float) -> float:
    """Grams for ``quantity`` × a named portion of ``gram_weight`` grams each.

    The portion-logging path resolves a named household measure ("1 cup, sliced" = 240 g) to
    grams here, then snapshots via :func:`scale_food`'s gram basis — so portion math never
    grows a second macro-scaling code path. Raises :class:`ValueError` when either input is
    non-positive.
    """
    if quantity <= 0:
        raise ValueError("quantity must be positive")
    if gram_weight <= 0:
        raise ValueError("portion gram weight must be positive")
    return quantity * gram_weight


def scale_food(food: FoodLike, quantity: float, unit: str) -> MacroSnapshot:
    """Compute the macro snapshot for logging ``quantity`` ``unit`` of ``food``.

    Raises :class:`ValueError` for a non-positive quantity or an unsupported unit.
    """
    if quantity <= 0:
        raise ValueError("quantity must be positive")
    unit_key = unit.strip().lower()

    if unit_key in GRAM_UNITS:
        return _from_basis(food, "_per_100g", quantity / 100.0)

    if unit_key in OUNCE_UNITS:
        grams = oz_to_g(quantity)
        return _from_basis(food, "_per_100g", grams / 100.0)

    if unit_key in SERVING_UNITS:
        if getattr(food, "kcal_per_serving", None) is not None:
            return _from_basis(food, "_per_serving", quantity)
        # No per-serving basis: fall back to the serving size in grams if the food defines one.
        if getattr(food, "serving_size", None):
            grams = quantity * food.serving_size
            return _from_basis(food, "_per_100g", grams / 100.0)
        raise ValueError("food has no serving basis; log it in grams instead")

    raise ValueError(f"unsupported unit: {unit!r}")

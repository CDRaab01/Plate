"""Normalized food representation shared by every :class:`~app.foods.base.FoodSource`.

Each source maps its own wire format onto :class:`NormalizedFood`, whose fields line up 1:1 with
the :class:`~app.models.food.Food` columns so caching is a straight ``Food(**item.to_food_kwargs())``.
"""
import re

from pydantic import BaseModel

# Collapses any run of whitespace; used by normalized_name for dedup keys.
_WHITESPACE = re.compile(r"\s+")


def normalized_name(name: str) -> str:
    """Canonical form of a food name for deduplication.

    Lower-cased, trimmed, internal whitespace collapsed. Intentionally conservative — we only
    fold differences that are clearly cosmetic so distinct foods are never merged.
    """
    return _WHITESPACE.sub(" ", name.strip().lower())


class NormalizedFood(BaseModel):
    """A source-agnostic nutrition record, ready to persist as a :class:`Food`."""

    source: str  # usda | off | user
    source_id: str | None = None
    name: str
    brand: str | None = None
    barcode: str | None = None
    serving_size: float | None = None
    serving_unit: str | None = None

    # Per 100g (primary basis; always populated)
    kcal_per_100g: float
    protein_g_per_100g: float
    carbs_g_per_100g: float
    fat_g_per_100g: float
    fiber_g_per_100g: float | None = None
    sugar_g_per_100g: float | None = None
    sat_fat_g_per_100g: float | None = None
    cholesterol_mg_per_100g: float | None = None
    sodium_mg_per_100g: float | None = None

    # Per serving (optional; present when the source defines a serving)
    kcal_per_serving: float | None = None
    protein_g_per_serving: float | None = None
    carbs_g_per_serving: float | None = None
    fat_g_per_serving: float | None = None
    fiber_g_per_serving: float | None = None
    sugar_g_per_serving: float | None = None
    sat_fat_g_per_serving: float | None = None
    cholesterol_mg_per_serving: float | None = None
    sodium_mg_per_serving: float | None = None

    def dedup_key(self) -> str:
        """Identity used to collapse duplicates within a single result set.

        Barcode is authoritative when present; otherwise fall back to the normalized name plus
        brand so the same product from two sources doesn't appear twice.
        """
        if self.barcode:
            return f"barcode:{self.barcode}"
        return f"name:{normalized_name(self.name)}|{normalized_name(self.brand or '')}"

    def to_food_kwargs(self) -> dict:
        return self.model_dump()

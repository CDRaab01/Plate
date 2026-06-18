"""Daily totals: sum the denormalized macro snapshots on a day's log entries.

Pure and unit-tested. Secondary nutrients are summed only over the entries that carry them, so a
missing micro on one entry doesn't poison the day's total (it simply isn't counted).
"""

from dataclasses import dataclass
from typing import Iterable, Protocol


class EntryLike(Protocol):
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float | None
    sugar_g: float | None
    sat_fat_g: float | None
    cholesterol_mg: float | None
    sodium_mg: float | None


@dataclass(frozen=True)
class Totals:
    kcal: float = 0.0
    protein_g: float = 0.0
    carbs_g: float = 0.0
    fat_g: float = 0.0
    fiber_g: float = 0.0
    sugar_g: float = 0.0
    sat_fat_g: float = 0.0
    cholesterol_mg: float = 0.0
    sodium_mg: float = 0.0


def sum_entries(entries: Iterable[EntryLike]) -> Totals:
    """Add up macros across ``entries``; ``None`` secondaries are treated as absent (not zero)."""
    kcal = protein = carbs = fat = 0.0
    fiber = sugar = sat_fat = cholesterol = sodium = 0.0
    for e in entries:
        kcal += e.kcal
        protein += e.protein_g
        carbs += e.carbs_g
        fat += e.fat_g
        fiber += e.fiber_g or 0.0
        sugar += e.sugar_g or 0.0
        sat_fat += e.sat_fat_g or 0.0
        cholesterol += e.cholesterol_mg or 0.0
        sodium += e.sodium_mg or 0.0
    return Totals(
        kcal=kcal,
        protein_g=protein,
        carbs_g=carbs,
        fat_g=fat,
        fiber_g=fiber,
        sugar_g=sugar,
        sat_fat_g=sat_fat,
        cholesterol_mg=cholesterol,
        sodium_mg=sodium,
    )

"""Pure macro-math tests: portion scaling and daily totals (CLAUDE.md §4, §10).

Table-driven, no DB — these functions are the single source of truth for logged macros, so they
get exhaustive coverage of the grams / serving bases and the secondary-nutrient handling.
"""
from dataclasses import dataclass

import pytest

from app.nutrition.portions import MacroSnapshot, scale_food
from app.nutrition.totals import Totals, sum_entries


@dataclass
class FakeFood:
    kcal_per_100g: float = 0.0
    protein_g_per_100g: float = 0.0
    carbs_g_per_100g: float = 0.0
    fat_g_per_100g: float = 0.0
    fiber_g_per_100g: float | None = None
    sugar_g_per_100g: float | None = None
    sat_fat_g_per_100g: float | None = None
    cholesterol_mg_per_100g: float | None = None
    sodium_mg_per_100g: float | None = None
    kcal_per_serving: float | None = None
    protein_g_per_serving: float | None = None
    carbs_g_per_serving: float | None = None
    fat_g_per_serving: float | None = None
    fiber_g_per_serving: float | None = None
    sugar_g_per_serving: float | None = None
    sat_fat_g_per_serving: float | None = None
    cholesterol_mg_per_serving: float | None = None
    sodium_mg_per_serving: float | None = None
    serving_size: float | None = None


BANANA = FakeFood(
    kcal_per_100g=89.0,
    protein_g_per_100g=1.1,
    carbs_g_per_100g=22.8,
    fat_g_per_100g=0.3,
    fiber_g_per_100g=2.6,
    serving_size=118.0,
)


def test_scale_grams_uses_per_100g_basis():
    snap = scale_food(BANANA, 200.0, "g")
    assert snap.kcal == pytest.approx(178.0)
    assert snap.protein_g == pytest.approx(2.2)
    assert snap.carbs_g == pytest.approx(45.6)
    assert snap.fat_g == pytest.approx(0.6)
    assert snap.fiber_g == pytest.approx(5.2)


@pytest.mark.parametrize(
    "quantity,unit,expected_kcal",
    [
        (100.0, "g", 89.0),
        (50.0, "g", 44.5),
        (1.0, "gram", 0.89),
        (250.0, "grams", 222.5),
    ],
)
def test_scale_grams_table(quantity, unit, expected_kcal):
    assert scale_food(BANANA, quantity, unit).kcal == pytest.approx(expected_kcal)


def test_scale_serving_uses_per_serving_basis_when_present():
    food = FakeFood(
        kcal_per_100g=89.0,
        protein_g_per_100g=1.1,
        carbs_g_per_100g=22.8,
        fat_g_per_100g=0.3,
        kcal_per_serving=105.0,
        protein_g_per_serving=1.3,
        carbs_g_per_serving=27.0,
        fat_g_per_serving=0.4,
    )
    snap = scale_food(food, 2.0, "serving")
    assert snap.kcal == pytest.approx(210.0)
    assert snap.carbs_g == pytest.approx(54.0)


def test_scale_serving_falls_back_to_serving_size_grams():
    # No per-serving basis, but serving_size=118g → one serving == 118g of the per-100g basis.
    snap = scale_food(BANANA, 1.0, "serving")
    assert snap.kcal == pytest.approx(89.0 * 1.18)


def test_scale_serving_without_basis_raises():
    food = FakeFood(kcal_per_100g=89.0, protein_g_per_100g=1.1, carbs_g_per_100g=22.8, fat_g_per_100g=0.3)
    with pytest.raises(ValueError):
        scale_food(food, 1.0, "serving")


def test_scale_rejects_non_positive_quantity():
    with pytest.raises(ValueError):
        scale_food(BANANA, 0.0, "g")


def test_scale_rejects_unknown_unit():
    with pytest.raises(ValueError):
        scale_food(BANANA, 1.0, "tablespoon")


def test_scale_preserves_none_secondary_nutrients():
    snap = scale_food(BANANA, 100.0, "g")
    assert snap.sugar_g is None  # banana fixture leaves sugar unset
    assert snap.fiber_g == pytest.approx(2.6)  # but fiber is set and scales


def _entry(**kw) -> MacroSnapshot:
    base = dict(kcal=0.0, protein_g=0.0, carbs_g=0.0, fat_g=0.0)
    base.update(kw)
    return MacroSnapshot(**base)


def test_sum_entries_empty_is_zero():
    assert sum_entries([]) == Totals()


def test_sum_entries_adds_primary_macros():
    totals = sum_entries(
        [
            _entry(kcal=100.0, protein_g=10.0, carbs_g=20.0, fat_g=5.0),
            _entry(kcal=200.0, protein_g=5.0, carbs_g=30.0, fat_g=8.0),
        ]
    )
    assert totals.kcal == pytest.approx(300.0)
    assert totals.protein_g == pytest.approx(15.0)
    assert totals.carbs_g == pytest.approx(50.0)
    assert totals.fat_g == pytest.approx(13.0)


def test_sum_entries_treats_missing_secondary_as_absent():
    totals = sum_entries(
        [
            _entry(kcal=100.0, fiber_g=3.0, sodium_mg=None),
            _entry(kcal=100.0, fiber_g=None, sodium_mg=50.0),
        ]
    )
    assert totals.fiber_g == pytest.approx(3.0)
    assert totals.sodium_mg == pytest.approx(50.0)

"""Pure unit-conversion tests (CLAUDE.md §11). Table-driven, no DB.

Storage is canonical metric; these are the lb↔kg / oz↔g conversions used at the API edges. Exact
NIST factors, so the round-trips are tight.
"""
import pytest

from app.nutrition.units import (
    g_to_oz,
    kg_to_display,
    kg_to_lb,
    lb_to_kg,
    oz_to_g,
    weight_to_kg,
)


@pytest.mark.parametrize(
    "lb,kg",
    [
        (0.0, 0.0),
        (1.0, 0.45359237),
        (100.0, 45.359237),
        (198.416, 90.0),  # ~90 kg
    ],
)
def test_lb_kg_round_trip(lb, kg):
    assert lb_to_kg(lb) == pytest.approx(kg)
    assert kg_to_lb(kg) == pytest.approx(lb, abs=1e-3)


@pytest.mark.parametrize(
    "oz,g",
    [
        (0.0, 0.0),
        (1.0, 28.349523125),
        (4.0, 113.3980925),
        (16.0, 453.59237),  # a pound of food
    ],
)
def test_oz_g_round_trip(oz, g):
    assert oz_to_g(oz) == pytest.approx(g)
    assert g_to_oz(g) == pytest.approx(oz)


def test_weight_to_kg_accepts_lb_and_kg():
    assert weight_to_kg(100.0, "kg") == pytest.approx(100.0)
    assert weight_to_kg(100.0, "LB") == pytest.approx(45.359237)
    assert weight_to_kg(70.0, " kg ") == pytest.approx(70.0)


def test_weight_to_kg_rejects_unknown_unit():
    with pytest.raises(ValueError):
        weight_to_kg(100.0, "stone")


def test_kg_to_display_respects_system():
    assert kg_to_display(90.0, "metric") == pytest.approx(90.0)
    assert kg_to_display(90.0, "imperial") == pytest.approx(kg_to_lb(90.0))

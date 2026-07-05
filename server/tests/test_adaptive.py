"""Pure adaptive-TDEE tests (ROADMAP2 T3 #1). Table-driven, no DB.

Covers the energy-balance solve, the adherence filter, the learning/insufficient/active status
gates, the confidence blend ramp, the deviation clamp, and the ``compute_targets`` override
plumbing. The DB-wired end-to-end (seed logs + weigh-ins → corrected ``/log`` targets) is exercised
by the live smoke, not here.
"""
import datetime

import pytest

from app.nutrition.adaptive import (
    ACTIVE,
    INSUFFICIENT_DATA,
    LEARNING,
    compute_adaptive_tdee,
)
from app.nutrition.targets import BodyProfile, compute_targets
from app.nutrition.trend import WeightPoint

WIN_START = datetime.date(2026, 6, 1)


def _intakes(kcal, n_days, *, start=WIN_START, light_days=0, light_kcal=500.0):
    """A window of ``n_days`` full days at ``kcal`` plus ``light_days`` sub-threshold days."""
    out = {}
    day = start
    for _ in range(n_days):
        out[day] = kcal
        day += datetime.timedelta(days=1)
    for _ in range(light_days):
        out[day] = light_kcal
        day += datetime.timedelta(days=1)
    return out


def _weights(pairs, *, start=WIN_START):
    """Weigh-ins as (day_offset, weight_kg) pairs."""
    return [WeightPoint(start + datetime.timedelta(days=off), w) for off, w in pairs]


# ── energy-balance solve (active) ─────────────────────────────────────────────


def test_active_cut_energy_balance():
    # 15 logged days @2000 kcal; weight 90→89 over 14 days = −0.5 kg/week.
    # observed maintenance = 2000 − (−0.5/7)·7700 = 2550. Blend w = 1.0·0.7 against formula 2400 →
    # 0.3·2400 + 0.7·2550 = 2505 (inside the ±30% clamp).
    r = compute_adaptive_tdee(
        _intakes(2000.0, 15), _weights([(0, 90.0), (14, 89.0)]), 2400.0, window_days=15
    )
    assert r.status == ACTIVE
    assert r.observed_maintenance == pytest.approx(2550.0)
    assert r.corrected_tdee == pytest.approx(2505.0)
    assert r.adjustment_kcal == pytest.approx(105.0)
    assert r.n_logged_days == 15
    assert r.confidence == pytest.approx(1.0)


def test_active_bulk_energy_balance():
    # intake 3000; weight 80→80.5 over 14 days = +0.25 kg/week → maintenance 3000 − 275 = 2725.
    r = compute_adaptive_tdee(
        _intakes(3000.0, 15), _weights([(0, 80.0), (14, 80.5)]), 2600.0, window_days=15
    )
    assert r.status == ACTIVE
    assert r.observed_maintenance == pytest.approx(2725.0)
    # 0.3·2600 + 0.7·2725 = 2687.5
    assert r.corrected_tdee == pytest.approx(2687.5)


def test_adherence_filter_excludes_light_days():
    # 13 full days @2000 + 2 days @500 (below MIN_LOGGED_KCAL). The light days must not count nor
    # drag the average, so the solve is identical to 13 clean days.
    r = compute_adaptive_tdee(
        _intakes(2000.0, 13, light_days=2),
        _weights([(0, 90.0), (14, 89.0)]),
        2400.0,
        window_days=15,
    )
    assert r.status == ACTIVE
    assert r.n_logged_days == 13
    assert r.observed_maintenance == pytest.approx(2550.0)  # avg is 2000, not dragged toward 500


# ── status gates ──────────────────────────────────────────────────────────────


def test_learning_when_too_few_logged_days():
    r = compute_adaptive_tdee(
        _intakes(2000.0, 6), _weights([(0, 90.0), (14, 89.0)]), 2400.0, window_days=15
    )
    assert r.status == LEARNING
    assert r.observed_maintenance is None
    assert r.corrected_tdee == pytest.approx(2400.0)  # targets stay on the formula
    assert r.adjustment_kcal == 0.0
    assert r.n_logged_days == 6


def test_learning_when_weigh_in_span_too_short():
    # Enough logged days, but weigh-ins span only 7 days (< MIN_WEIGH_IN_SPAN_DAYS).
    r = compute_adaptive_tdee(
        _intakes(2000.0, 12), _weights([(0, 90.0), (7, 89.5)]), 2400.0, window_days=14
    )
    assert r.status == LEARNING
    assert r.corrected_tdee == pytest.approx(2400.0)


def test_insufficient_when_no_weight_signal():
    # One weigh-in → no least-squares rate → can't solve at all.
    r = compute_adaptive_tdee(
        _intakes(2000.0, 12), _weights([(0, 90.0)]), 2400.0, window_days=14
    )
    assert r.status == INSUFFICIENT_DATA
    assert r.observed_maintenance is None
    assert r.corrected_tdee == pytest.approx(2400.0)


def test_insufficient_when_no_data():
    r = compute_adaptive_tdee({}, [], 2400.0)
    assert r.status == INSUFFICIENT_DATA
    assert r.n_logged_days == 0
    assert r.corrected_tdee == pytest.approx(2400.0)
    assert r.confidence == 0.0


# ── confidence blend + clamp ──────────────────────────────────────────────────


def test_confidence_scales_with_coverage():
    # 10 logged days in a 14-day window → coverage 10/14; blend weight is coverage·MAX_BLEND.
    r = compute_adaptive_tdee(
        _intakes(2000.0, 10), _weights([(0, 90.0), (13, 89.0)]), 2400.0, window_days=14
    )
    assert r.status == ACTIVE
    assert r.confidence == pytest.approx(10 / 14)


def test_deviation_clamp_high():
    # An absurdly high observed maintenance is clamped to formula × 1.30.
    r = compute_adaptive_tdee(
        _intakes(4000.0, 15), _weights([(0, 90.0), (14, 89.0)]), 2000.0, window_days=15
    )
    assert r.status == ACTIVE
    assert r.corrected_tdee == pytest.approx(2000.0 * 1.30)  # clamped
    assert r.observed_maintenance == pytest.approx(4550.0)  # raw estimate still reported


def test_deviation_clamp_low():
    # Low intake + weight gain → observed maintenance far below formula → clamped to formula × 0.70.
    r = compute_adaptive_tdee(
        _intakes(1000.0, 15), _weights([(0, 80.0), (14, 82.0)]), 2500.0, window_days=15
    )
    assert r.status == ACTIVE
    assert r.corrected_tdee == pytest.approx(2500.0 * 0.70)  # clamped


# ── compute_targets override plumbing ─────────────────────────────────────────


def test_compute_targets_override_shifts_kcal_by_delta():
    profile = BodyProfile(
        weight_kg=80.0,
        height_cm=180.0,
        age=30,
        sex="male",
        activity_level="moderate",
        goal_type="cut",
        rate_kg_per_week=-0.5,
    )
    base = compute_targets(profile)
    # Override maintenance 300 kcal above the formula; goal adjustment is additive, so (both above
    # the MIN_DAILY_KCAL floor) the kcal target rises by exactly the override delta.
    from app.nutrition.targets import tdee

    formula = tdee(80.0, 180.0, 30, "male", "moderate")
    bumped = compute_targets(profile, maintenance_override=formula + 300.0)
    assert bumped.kcal - base.kcal == pytest.approx(300.0)


def test_compute_targets_override_none_is_formula():
    profile = BodyProfile(
        weight_kg=70.0,
        height_cm=170.0,
        age=40,
        sex="female",
        activity_level="light",
        goal_type="maintain",
        rate_kg_per_week=0.0,
    )
    assert compute_targets(profile) == compute_targets(profile, maintenance_override=None)

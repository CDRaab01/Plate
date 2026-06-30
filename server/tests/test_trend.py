"""Pure weight-trend / on-pace tests (CLAUDE.md §7, §10). Table-driven, no DB.

Covers the least-squares rate, the moving-average smoothing, and the direction-aware on-pace
classification for cut / bulk / maintain plus the insufficient-data path.
"""
import datetime

import pytest

from app.nutrition.trend import (
    AHEAD,
    BEHIND,
    INSUFFICIENT_DATA,
    ON_PACE,
    WeightPoint,
    classify_pace,
    compute_trend,
    moving_average,
    observed_rate_kg_per_week,
)

D0 = datetime.date(2026, 6, 1)


def _series(weights, step_days=7):
    """Weigh-ins spaced ``step_days`` apart starting at D0."""
    return [WeightPoint(D0 + datetime.timedelta(days=i * step_days), w) for i, w in enumerate(weights)]


# ── observed rate ─────────────────────────────────────────────────────────────


def test_observed_rate_none_below_two_points():
    assert observed_rate_kg_per_week([]) is None
    assert observed_rate_kg_per_week(_series([90.0])) is None


def test_observed_rate_none_when_all_same_date():
    pts = [WeightPoint(D0, 90.0), WeightPoint(D0, 89.0)]
    assert observed_rate_kg_per_week(pts) is None


def test_observed_rate_linear_loss():
    # −0.5 kg every 7 days → −0.5 kg/week.
    rate = observed_rate_kg_per_week(_series([90.0, 89.5, 89.0, 88.5]))
    assert rate == pytest.approx(-0.5)


def test_observed_rate_gain():
    rate = observed_rate_kg_per_week(_series([80.0, 80.25, 80.5]))
    assert rate == pytest.approx(0.25)


# ── moving average ────────────────────────────────────────────────────────────


def test_moving_average_trailing_window():
    pts = _series([90.0, 88.0, 86.0])
    ma = moving_average(pts, window=2)
    assert [round(p.weight, 2) for p in ma] == [90.0, 89.0, 87.0]  # 90, (90+88)/2, (88+86)/2


def test_moving_average_window_one_is_identity():
    pts = _series([90.0, 88.0])
    assert [p.weight for p in moving_average(pts, 1)] == [90.0, 88.0]


# ── classify_pace ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "observed,goal,expected",
    [
        (None, -0.5, INSUFFICIENT_DATA),
        # Cut (goal −0.5): on pace within tol, ahead when losing faster, behind when slower/gaining.
        (-0.5, -0.5, ON_PACE),
        (-0.9, -0.5, AHEAD),
        (-0.1, -0.5, BEHIND),
        (0.2, -0.5, BEHIND),
        # Bulk (goal +0.25): faster gain is ahead, slower is behind.
        (0.25, 0.25, ON_PACE),
        (0.6, 0.25, AHEAD),
        (0.0, 0.25, BEHIND),
        # Maintain (goal 0): within tol on pace, any drift beyond tol is behind.
        (0.05, 0.0, ON_PACE),
        (0.4, 0.0, BEHIND),
        (-0.4, 0.0, BEHIND),
    ],
)
def test_classify_pace(observed, goal, expected):
    assert classify_pace(observed, goal, tol=0.15) == expected


# ── compute_trend ─────────────────────────────────────────────────────────────


def test_compute_trend_empty():
    t = compute_trend([], -0.5)
    assert t.points == []
    assert t.trend_weight_kg is None
    assert t.observed_rate_kg_per_week is None
    assert t.status == INSUFFICIENT_DATA
    assert t.goal_rate_kg_per_week == -0.5


def test_compute_trend_on_pace_cut():
    # Daily −0.0714 kg ≈ −0.5/week over the last 14 days.
    weights = [90.0 - 0.0714 * i for i in range(15)]
    t = compute_trend(_series(weights, step_days=1), -0.5)
    assert t.status == ON_PACE
    assert t.trend_weight_kg is not None
    assert t.observed_rate_kg_per_week == pytest.approx(-0.5, abs=0.05)


def test_compute_trend_sorts_unordered_input():
    pts = [WeightPoint(D0 + datetime.timedelta(days=7), 89.0), WeightPoint(D0, 90.0)]
    t = compute_trend(pts, 0.0)
    # Latest smoothed point should reflect the later (89.0) date, not input order.
    assert t.points[-1].date == D0 + datetime.timedelta(days=7)

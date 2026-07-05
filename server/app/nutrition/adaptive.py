"""Adaptive TDEE correction (pure, table-tested — CLAUDE.md §7; ROADMAP2 T3 #1).

The formula TDEE (Mifflin-St Jeor × activity factor, see :mod:`app.nutrition.targets`) is a
population estimate; a given person's real maintenance drifts from it. This module back-solves the
user's *observed* maintenance from the energy balance between what they actually logged and how
their weight actually moved over a trailing window, then blends that into the formula so targets
self-correct.

Energy balance, per day: ``intake − maintenance`` is the surplus/deficit, and a surplus/deficit of
:data:`~app.nutrition.constants.KCAL_PER_KG` kcal moves bodyweight by 1 kg. So if the user averaged
``avg_intake`` while their (least-squares) weight rate was ``r`` kg/week::

    observed_maintenance = avg_intake − (r / 7) × KCAL_PER_KG

Eat ``avg_intake`` and *gain* → you were in surplus → true maintenance is *below* intake; *lose* →
maintenance is *above* it.

Like the rest of :mod:`app.nutrition`, everything here is a pure function of its inputs — the
service layer loads the window's intakes + weigh-ins + the formula TDEE and passes them in. The
correction is deliberately conservative: the formula always anchors the blend (capped at
:data:`~app.nutrition.constants.MAX_BLEND`) and the result is clamped to
±:data:`~app.nutrition.constants.MAX_TDEE_DEVIATION` of the formula so a water-weight swing can't
run away with a user's targets.
"""

import datetime
from dataclasses import dataclass

from app.nutrition import constants as c
from app.nutrition.trend import WeightPoint, observed_rate_kg_per_week

# Status values.
INSUFFICIENT_DATA = "insufficient_data"  # not enough logged days / weigh-in signal to start
LEARNING = "learning"  # some data, but below the thresholds to trust a correction
ACTIVE = "active"  # correction is applied to targets


@dataclass(frozen=True)
class AdaptiveTDEE:
    formula_tdee: float  # Mifflin-St Jeor × activity maintenance (the anchor)
    corrected_tdee: float  # what targets actually use (== formula unless status is ACTIVE)
    observed_maintenance: float | None  # energy-balance estimate; None unless ACTIVE
    adjustment_kcal: float  # corrected − formula (0 unless ACTIVE)
    confidence: float  # 0..1 window coverage (logged days / window days)
    n_logged_days: int
    window_days: int
    min_logged_days: int  # threshold the client shows progress against ("{n}/{min} days")
    status: str  # INSUFFICIENT_DATA | LEARNING | ACTIVE


def _weigh_in_span_days(points: list[WeightPoint]) -> int:
    """Calendar days between the earliest and latest weigh-in (0 for < 2 points)."""
    if len(points) < 2:
        return 0
    dates = [p.date for p in points]
    return (max(dates) - min(dates)).days


def compute_adaptive_tdee(
    daily_intakes: dict[datetime.date, float],
    weight_points: list[WeightPoint],
    formula_tdee: float,
    *,
    window_days: int = c.ADAPTIVE_WINDOW_DAYS,
    min_logged_kcal: float = c.MIN_LOGGED_KCAL,
    min_logged_days: int = c.MIN_LOGGED_DAYS,
    min_weigh_in_span_days: int = c.MIN_WEIGH_IN_SPAN_DAYS,
    max_blend: float = c.MAX_BLEND,
    max_deviation: float = c.MAX_TDEE_DEVIATION,
) -> AdaptiveTDEE:
    """Solve observed maintenance and blend it into ``formula_tdee``.

    ``daily_intakes`` maps each date in the window to its total logged kcal; days below
    ``min_logged_kcal`` are treated as not-logged (a half-empty day would understate intake). The
    correction is only *applied* (:data:`ACTIVE`) when there are ≥ ``min_logged_days`` logged days
    and the weigh-ins span ≥ ``min_weigh_in_span_days`` with a usable least-squares rate; short of
    that it reports :data:`LEARNING` (some signal) or :data:`INSUFFICIENT_DATA` (none), leaving
    targets on the formula.
    """
    logged = [kcal for kcal in daily_intakes.values() if kcal >= min_logged_kcal]
    n_logged = len(logged)
    coverage = min(1.0, n_logged / window_days) if window_days > 0 else 0.0

    rate = observed_rate_kg_per_week(sorted(weight_points, key=lambda p: p.date))
    span = _weigh_in_span_days(weight_points)

    base = AdaptiveTDEE(
        formula_tdee=formula_tdee,
        corrected_tdee=formula_tdee,
        observed_maintenance=None,
        adjustment_kcal=0.0,
        confidence=coverage,
        n_logged_days=n_logged,
        window_days=window_days,
        min_logged_days=min_logged_days,
        status=INSUFFICIENT_DATA,
    )

    # No intake signal or no weight signal at all → can't even provisionally estimate.
    if n_logged == 0 or rate is None:
        return base

    avg_intake = sum(logged) / n_logged
    observed_maintenance = avg_intake - (rate / c.DAYS_PER_WEEK) * c.KCAL_PER_KG

    # Have *some* signal but below the trust thresholds → learning (targets stay on the formula).
    if n_logged < min_logged_days or span < min_weigh_in_span_days:
        from dataclasses import replace

        return replace(base, status=LEARNING)

    # Enough data → blend, then clamp the deviation from the formula.
    weight = coverage * max_blend
    blended = (1.0 - weight) * formula_tdee + weight * observed_maintenance
    lo = formula_tdee * (1.0 - max_deviation)
    hi = formula_tdee * (1.0 + max_deviation)
    corrected = min(max(blended, lo), hi)

    return AdaptiveTDEE(
        formula_tdee=formula_tdee,
        corrected_tdee=corrected,
        observed_maintenance=observed_maintenance,
        adjustment_kcal=corrected - formula_tdee,
        confidence=coverage,
        n_logged_days=n_logged,
        window_days=window_days,
        min_logged_days=min_logged_days,
        status=ACTIVE,
    )

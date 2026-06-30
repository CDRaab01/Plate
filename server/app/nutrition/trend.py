"""Weight-trend + on-pace classification (pure, table-tested — CLAUDE.md §7, §11).

Given a series of weigh-ins (kg) and the user's goal rate (kg/week), this computes:

* a **smoothed trend line** (trailing moving average) to damp day-to-day noise for charting,
* the **observed weekly rate** of weight change (least-squares slope over the recent window), and
* an **on-pace status** comparing observed vs goal rate, direction-aware for cut/bulk/maintain.

Like the rest of :mod:`app.nutrition`, this is a pure function of its inputs — the service layer
loads the weigh-ins and the goal rate and passes them in; the client only renders the result.
"""

import datetime
from dataclasses import dataclass

from app.nutrition import constants as c

# On-pace status values.
ON_PACE = "on_pace"
AHEAD = "ahead"
BEHIND = "behind"
INSUFFICIENT_DATA = "insufficient_data"


@dataclass(frozen=True)
class WeightPoint:
    date: datetime.date
    weight: float  # kg


@dataclass(frozen=True)
class WeightTrend:
    points: list[WeightPoint]  # smoothed (moving-average) series, oldest first
    trend_weight_kg: float | None  # latest smoothed weight, or None if no data
    observed_rate_kg_per_week: float | None  # None when < 2 usable points
    goal_rate_kg_per_week: float
    status: str  # ON_PACE | AHEAD | BEHIND | INSUFFICIENT_DATA


def moving_average(points: list[WeightPoint], window: int) -> list[WeightPoint]:
    """Trailing simple moving average: each output point averages up to ``window`` preceding
    weigh-ins (inclusive). Input is assumed oldest-first; output keeps each point's own date."""
    if window < 1:
        raise ValueError("window must be >= 1")
    out: list[WeightPoint] = []
    for i, p in enumerate(points):
        start = max(0, i - window + 1)
        chunk = points[start : i + 1]
        avg = sum(q.weight for q in chunk) / len(chunk)
        out.append(WeightPoint(date=p.date, weight=avg))
    return out


def observed_rate_kg_per_week(points: list[WeightPoint]) -> float | None:
    """Least-squares slope of weight vs time, expressed in kg/week.

    Returns ``None`` when there are fewer than two points or every point shares the same date
    (no time spread → slope undefined).
    """
    if len(points) < 2:
        return None
    xs = [float(p.date.toordinal()) for p in points]  # days
    ys = [p.weight for p in points]
    n = len(points)
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    var_x = sum((x - mean_x) ** 2 for x in xs)
    if var_x == 0:
        return None
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    slope_per_day = cov / var_x
    return slope_per_day * c.DAYS_PER_WEEK


def classify_pace(
    observed: float | None,
    goal: float,
    *,
    tol: float = c.ON_PACE_TOLERANCE_KG_PER_WEEK,
) -> str:
    """Compare the observed weekly rate to the goal rate, direction-aware.

    * Within ``tol`` of goal → ``on_pace``.
    * Otherwise, *in the goal's intended direction* (lose for a cut, gain for a bulk) → ``ahead``;
      lagging or moving the wrong way → ``behind``. A maintain goal (≈0) treats any drift beyond
      ``tol`` as ``behind``.
    * No usable observed rate → ``insufficient_data``.
    """
    if observed is None:
        return INSUFFICIENT_DATA
    diff = observed - goal
    if abs(diff) <= tol:
        return ON_PACE
    if goal > 0:  # bulk: faster gain is ahead
        return AHEAD if diff > 0 else BEHIND
    if goal < 0:  # cut: faster loss (more negative) is ahead
        return AHEAD if diff < 0 else BEHIND
    return BEHIND  # maintain: any drift beyond tol is off track


def _recent(points: list[WeightPoint], window_days: int) -> list[WeightPoint]:
    """The weigh-ins within ``window_days`` of the most recent one (inclusive)."""
    if not points:
        return []
    cutoff = points[-1].date - datetime.timedelta(days=window_days)
    return [p for p in points if p.date >= cutoff]


def compute_trend(
    points: list[WeightPoint],
    goal_rate: float,
    *,
    window_days: int = c.TREND_WINDOW_DAYS,
    smoothing: int = c.TREND_SMOOTHING_POINTS,
    tol: float = c.ON_PACE_TOLERANCE_KG_PER_WEEK,
) -> WeightTrend:
    """Assemble the smoothed series, recent observed rate, and on-pace status."""
    ordered = sorted(points, key=lambda p: p.date)
    smoothed = moving_average(ordered, smoothing) if ordered else []
    trend_weight = smoothed[-1].weight if smoothed else None
    observed = observed_rate_kg_per_week(_recent(ordered, window_days))
    return WeightTrend(
        points=smoothed,
        trend_weight_kg=trend_weight,
        observed_rate_kg_per_week=observed,
        goal_rate_kg_per_week=goal_rate,
        status=classify_pace(observed, goal_rate, tol=tol),
    )

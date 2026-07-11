"""Spotter-awareness: reading the user's training status (CLAUDE.md §7, §8).

Plate's daily targets get a training-day bump when the user trained. *Whether* they trained comes
from Spotter, behind a small :class:`WorkoutSource` abstraction so the rest of the app (and the
tests) never depend on the transport:

* :class:`SpotterWorkoutSource` — the real path: a short-lived cross-app JWT (signed with the shared
  ``cross_app_secret``, carrying the user's email) authenticates a read-only ``GET /workouts`` call.
* :class:`NullWorkoutSource` — the default when the integration isn't configured (and in CI): never
  a training day, so targets fall back to the plain goal-based numbers.

:func:`is_training_day` is the one entry point callers use; it's **best-effort** — any failure
(Spotter down, bad config, malformed reply) degrades to "not a training day" rather than failing the
user's request, and is logged.
"""

import datetime
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

import httpx

from app.config import settings
from app.services.cross_app_token import cross_app_configured, fetch_cross_app_token

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class WorkoutStatus:
    """Did the user train on a day, with a light breakdown (mirrors Spotter's ``WorkoutDayOut``)."""

    trained: bool
    strength_sessions: int = 0
    cardio_sessions: int = 0


# Shared "no workout" sentinel — returned whenever the integration is off or a lookup fails.
NOT_TRAINED = WorkoutStatus(trained=False)


@dataclass(frozen=True)
class WeekSummary:
    """Training over a date range (mirrors Spotter's ``WorkoutRangeOut.totals`` — federated
    awareness Link B). ``None`` where a summary would go means "Spotter didn't say", never
    "didn't train" — absence and zero are different facts."""

    days_trained: int
    strength_sessions: int
    cardio_sessions: int


class WorkoutSource(ABC):
    """Resolves a user's training status for a date. Implementations may hit the network or not."""

    @abstractmethod
    async def trained_on(self, email: str, day: datetime.date) -> WorkoutStatus:
        raise NotImplementedError

    async def trained_week(
        self, email: str, start: datetime.date, end: datetime.date
    ) -> WeekSummary | None:
        """Range summary via Spotter's ``GET /workouts?start=&end=``. Non-abstract default (None)
        so null/stub sources and older fakes stay valid — absence degrades the coach's weekly
        framing, nothing else."""
        return None


class NullWorkoutSource(WorkoutSource):
    """No integration configured → never a training day. The safe default (and CI behaviour)."""

    async def trained_on(self, email: str, day: datetime.date) -> WorkoutStatus:
        return NOT_TRAINED


class SpotterWorkoutSource(WorkoutSource):
    """Reads training status from Spotter's read-only ``GET /workouts?date=`` over HTTP.

    ``client`` is injectable so tests drive a mocked transport and CI never reaches a real server;
    in production it's ``None`` and a client is opened per call.
    """

    def __init__(self, base_url: str, *, client: httpx.AsyncClient | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = client

    async def trained_on(self, email: str, day: datetime.date) -> WorkoutStatus:
        token = await fetch_cross_app_token(email)
        request = lambda c: c.get(  # noqa: E731 - tiny local binding, reused for both client paths
            f"{self._base_url}/workouts",
            params={"date": day.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
        )
        if self._client is not None:
            resp = await request(self._client)
        else:
            async with httpx.AsyncClient(timeout=settings.spotter_timeout_seconds) as client:
                resp = await request(client)
        resp.raise_for_status()
        data = resp.json()
        return WorkoutStatus(
            trained=bool(data["trained"]),
            strength_sessions=int(data.get("strength_sessions", 0)),
            cardio_sessions=int(data.get("cardio_sessions", 0)),
        )

    async def trained_week(
        self, email: str, start: datetime.date, end: datetime.date
    ) -> WeekSummary | None:
        token = await fetch_cross_app_token(email)
        request = lambda c: c.get(  # noqa: E731 - tiny local binding, mirrors trained_on
            f"{self._base_url}/workouts",
            params={"start": start.isoformat(), "end": end.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
        )
        if self._client is not None:
            resp = await request(self._client)
        else:
            async with httpx.AsyncClient(timeout=settings.spotter_timeout_seconds) as client:
                resp = await request(client)
        resp.raise_for_status()
        totals = resp.json()["totals"]
        return WeekSummary(
            days_trained=int(totals.get("days_trained", 0)),
            strength_sessions=int(totals.get("strength_sessions", 0)),
            cardio_sessions=int(totals.get("cardio_sessions", 0)),
        )


def get_workout_source() -> WorkoutSource:
    """FastAPI dependency: the configured source, or the null source when integration is off.

    Returned as a Depends so route tests can override it (``app.dependency_overrides``) without
    touching the network.
    """
    if settings.spotter_base_url and cross_app_configured():
        return SpotterWorkoutSource(settings.spotter_base_url)
    return NullWorkoutSource()


async def is_training_day(
    email: str, day: datetime.date, *, source: WorkoutSource | None = None
) -> bool:
    """Best-effort: did the user train on ``day``? Any failure degrades to ``False`` (and is logged).

    Targets are useful with or without Spotter, so a Spotter outage must never break the diary or
    the coach — it just means no training-day bump for that request.
    """
    source = source or get_workout_source()
    try:
        status = await source.trained_on(email, day)
        return status.trained
    except Exception as exc:  # noqa: BLE001 - intentional: degrade gracefully, never propagate
        log.warning("workout-source lookup failed for %s on %s: %s", email, day, exc)
        return False


async def training_week(
    email: str, day: datetime.date, *, source: WorkoutSource | None = None
) -> WeekSummary | None:
    """Best-effort: the last 7 days of training ending on ``day`` (federated awareness Link B).
    Any failure — or a source that doesn't do ranges — degrades to ``None``: the coach simply
    loses its weekly framing line, never the request."""
    source = source or get_workout_source()
    try:
        return await source.trained_week(email, day - datetime.timedelta(days=6), day)
    except Exception as exc:  # noqa: BLE001 - intentional: degrade gracefully, never propagate
        log.warning("workout-week lookup failed for %s ending %s: %s", email, day, exc)
        return None

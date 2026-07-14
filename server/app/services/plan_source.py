"""Cookbook-awareness: reading tonight's planned meal (federated awareness Link E, CROSS-APP.md
rule 7).

Mirrors `workout_source.py` exactly — a small :class:`PlanSource` abstraction so the coach and the
tests never depend on the transport:

* :class:`CookbookPlanSource` — the real path: an RS256 cross-app token authenticates a read-only
  ``GET /cross-app/plan?date=`` call to Cookbook.
* :class:`NullPlanSource` — the default when the integration isn't configured (and in CI): no plan.

:func:`planned_meals` is the one entry point callers use; it's **best-effort** — any failure
(Cookbook down, bad config, malformed reply) degrades to an empty plan rather than failing the
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
class PlannedMeal:
    slot: str  # breakfast | lunch | dinner | snack
    name: str  # recipe name, or a free-text note
    eaten: bool = False  # Cookbook reports whether the meal was actually eaten (not just planned)


class PlanSource(ABC):
    """Resolves the user's planned meals for a date. Implementations may hit the network or not."""

    @abstractmethod
    async def planned_on(self, email: str, day: datetime.date) -> list[PlannedMeal]:
        raise NotImplementedError


class NullPlanSource(PlanSource):
    """No integration configured → no plan. The safe default (and CI behaviour)."""

    async def planned_on(self, email: str, day: datetime.date) -> list[PlannedMeal]:
        return []


class CookbookPlanSource(PlanSource):
    """Reads the day's plan from Cookbook's read-only ``GET /cross-app/plan?date=`` over HTTP.

    ``client`` is injectable so tests drive a mocked transport and CI never reaches a real server;
    in production it's ``None`` and a client is opened per call.
    """

    def __init__(self, base_url: str, *, client: httpx.AsyncClient | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = client

    async def planned_on(self, email: str, day: datetime.date) -> list[PlannedMeal]:
        token = await fetch_cross_app_token(email)
        request = lambda c: c.get(  # noqa: E731 - tiny local binding, mirrors workout_source
            f"{self._base_url}/cross-app/plan",
            params={"date": day.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
        )
        if self._client is not None:
            resp = await request(self._client)
        else:
            async with httpx.AsyncClient(timeout=settings.cookbook_timeout_seconds) as client:
                resp = await request(client)
        resp.raise_for_status()
        entries = resp.json().get("entries", [])
        return [
            PlannedMeal(slot=e["slot"], name=e["recipe_name"], eaten=bool(e.get("eaten", False)))
            for e in entries
            if e.get("recipe_name")
        ]


def get_plan_source() -> PlanSource:
    """FastAPI dependency: the configured source, or the null source when integration is off."""
    if settings.cookbook_base_url and cross_app_configured():
        return CookbookPlanSource(settings.cookbook_base_url)
    return NullPlanSource()


async def planned_meals(
    email: str, day: datetime.date, *, source: PlanSource | None = None
) -> list[PlannedMeal]:
    """Best-effort: the user's planned meals for ``day``. Any failure degrades to ``[]`` (and is
    logged) — a Cookbook outage must never break the coach; it just means no plan framing."""
    source = source or get_plan_source()
    try:
        return await source.planned_on(email, day)
    except Exception as exc:  # noqa: BLE001 - intentional: degrade gracefully, never propagate
        log.warning("plan-source lookup failed for %s on %s: %s", email, day, exc)
        return []

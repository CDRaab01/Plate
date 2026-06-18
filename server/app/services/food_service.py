"""Food search & caching (CLAUDE.md §5).

Search strategy: **local ``foods`` table first → USDA → OFF**. A query that hits the local cache
never touches the network; on a miss we fan out to the external sources, normalize + deduplicate
their results, persist the new ones, and return them — so the next identical search is local.
"""

import logging

import httpx
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood, normalized_name
from app.foods.off import OpenFoodFactsSource
from app.foods.usda import UsdaFoodSource
from app.models.food import Food

log = logging.getLogger(__name__)


async def _search_local(db: AsyncSession, query: str, limit: int) -> list[Food]:
    """Case-insensitive substring match on the cached ``foods`` table."""
    pattern = f"%{query}%"
    result = await db.execute(
        select(Food).where(Food.name.ilike(pattern)).order_by(Food.name).limit(limit)
    )
    return list(result.scalars().all())


def _build_live_sources(client: httpx.AsyncClient) -> list[FoodSource]:
    """Construct the external sources in priority order (USDA before OFF).

    USDA is included only when an API key is configured — without it the FDC API rejects calls,
    so we skip it rather than waste a round-trip.
    """
    sources: list[FoodSource] = []
    if settings.usda_api_key:
        sources.append(UsdaFoodSource(client, settings.usda_api_key, settings.usda_base_url))
    sources.append(OpenFoodFactsSource(client, settings.off_base_url, settings.off_user_agent))
    return sources


async def _gather(sources: list[FoodSource], query: str, limit: int) -> list[NormalizedFood]:
    """Search each source in order; a failing source is logged and skipped, not fatal."""
    items: list[NormalizedFood] = []
    for source in sources:
        try:
            items.extend(await source.search(query, limit=limit))
        except Exception as exc:  # noqa: BLE001 — one bad source shouldn't sink the whole search
            log.warning("Food source %s failed for %r: %s", source.source_tag, query, exc)
    return items


async def _find_existing(db: AsyncSession, item: NormalizedFood) -> Food | None:
    """Return an already-cached row matching ``item`` (barcode → source id → exact lower name)."""
    if item.barcode:
        found = (
            (await db.execute(select(Food).where(Food.barcode == item.barcode))).scalars().first()
        )
        if found:
            return found
    if item.source_id:
        found = (
            (
                await db.execute(
                    select(Food).where(Food.source == item.source, Food.source_id == item.source_id)
                )
            )
            .scalars()
            .first()
        )
        if found:
            return found
    found = (
        (await db.execute(select(Food).where(func.lower(Food.name) == normalized_name(item.name))))
        .scalars()
        .first()
    )
    return found


async def cache_foods(db: AsyncSession, items: list[NormalizedFood]) -> list[Food]:
    """Persist new normalized foods, collapsing duplicates within the batch and against the DB.

    Deduplication is by barcode and by normalized name (CLAUDE.md §5). Existing rows are reused so
    repeated searches don't grow the table.
    """
    results: list[Food] = []
    seen: set[str] = set()
    new_rows: list[Food] = []
    for item in items:
        key = item.dedup_key()
        if key in seen:
            continue
        seen.add(key)
        existing = await _find_existing(db, item)
        if existing is not None:
            results.append(existing)
            continue
        food = Food(**item.to_food_kwargs())
        db.add(food)
        new_rows.append(food)
        results.append(food)

    if new_rows:
        await db.commit()
        for food in new_rows:
            await db.refresh(food)
    return results


async def search_foods(
    db: AsyncSession,
    query: str,
    *,
    sources: list[FoodSource] | None = None,
) -> list[Food]:
    """Local-cache-first food search.

    ``sources`` is injectable for tests; in production it's ``None`` and live USDA/OFF sources are
    built on demand (and only when :data:`settings.food_search_live` is enabled).
    """
    q = query.strip()
    if not q:
        return []

    local = await _search_local(db, q, settings.external_search_limit)
    if local:
        return local

    if sources is not None:
        items = await _gather(sources, q, settings.external_search_limit)
        return await cache_foods(db, items)

    if not settings.food_search_live:
        return []

    async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
        live = _build_live_sources(client)
        items = await _gather(live, q, settings.external_search_limit)
    return await cache_foods(db, items)


async def _fetch_barcode(source: FoodSource, code: str) -> NormalizedFood | None:
    """Resolve one barcode, treating a source failure as 'not found' rather than fatal."""
    try:
        return await source.fetch_barcode(code)
    except Exception as exc:  # noqa: BLE001 — a flaky OFF call shouldn't 500 the scan path
        log.warning("Barcode lookup failed for %r: %s", code, exc)
        return None


async def lookup_barcode(
    db: AsyncSession,
    barcode: str,
    *,
    source: FoodSource | None = None,
) -> Food | None:
    """Resolve a scanned barcode to a cached :class:`Food` (CLAUDE.md §6).

    Local cache first: a barcode we've seen before never hits the network. On a miss we go straight
    to Open Food Facts (the barcode authority — CLAUDE.md §5), normalize + cache the product, and
    return it so the next scan is local.

    ``source`` is injectable for tests; in production it's ``None`` and a live OFF source is built
    on demand (and only when :data:`settings.food_search_live` is enabled).
    """
    code = barcode.strip()
    if not code:
        return None

    existing = (await db.execute(select(Food).where(Food.barcode == code))).scalars().first()
    if existing is not None:
        return existing

    if source is not None:
        normalized = await _fetch_barcode(source, code)
    elif settings.food_search_live:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
            off = OpenFoodFactsSource(client, settings.off_base_url, settings.off_user_agent)
            normalized = await _fetch_barcode(off, code)
    else:
        return None

    if normalized is None:
        return None
    cached = await cache_foods(db, [normalized])
    return cached[0] if cached else None


async def create_custom_food(db: AsyncSession, data: dict) -> Food:
    """Insert a user-defined food (``source='user'``)."""
    food = Food(source="user", **data)
    db.add(food)
    await db.commit()
    await db.refresh(food)
    return food


async def get_food(db: AsyncSession, food_id) -> Food | None:
    return await db.get(Food, food_id)

"""Food search & caching (CLAUDE.md §5).

Search strategy: **local ``foods`` table first → USDA → OFF**, with a per-query TTL deciding
when the network is consulted. A query whose external fetch is still fresh is served from the
local cache alone; a stale query with a healthy local page returns instantly and refreshes from
the external sources in the background; a stale, thin query blocks on the fan-out so first-time
searches still surface external data. Local matching is token-AND with a trigram-similarity
fallback (pg_trgm) when the strict match comes up thin, so typos and plural mismatches still
find the cached food.
"""

import asyncio
import datetime
import logging
import re

import httpx
from fastapi import BackgroundTasks
from sqlalchemy import and_, func, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import settings
from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood, normalized_name
from app.foods.off import OpenFoodFactsSource
from app.foods.ranking import rank_foods
from app.foods.usda import UsdaFoodSource
from app.models.food import Food
from app.models.food_log_entry import FoodLogEntry
from app.models.food_portion import FoodPortion
from app.models.search_query import SearchQuery

log = logging.getLogger(__name__)

#: Valid values for the search ``filter`` param (validated at the router).
FILTERS = ("all", "generic", "branded", "mine")

#: Fuzzy-fallback candidate cap — generous enough that ranking sees the strong matches.
_FUZZY_LIMIT = 50


async def _recent_food_rank(db: AsyncSession, user_id) -> dict:
    """{food_id: rank} for the user's logged foods, most-recently-logged first (rank 0)."""
    result = await db.execute(
        select(FoodLogEntry.food_id, func.max(FoodLogEntry.created_at).label("last"))
        .where(FoodLogEntry.user_id == user_id, FoodLogEntry.food_id.is_not(None))
        .group_by(FoodLogEntry.food_id)
        .order_by(func.max(FoodLogEntry.created_at).desc())
        .limit(200)
    )
    return {food_id: rank for rank, (food_id, _) in enumerate(result.all())}


def _filter_conditions(search_filter: str, user_id) -> list:
    """Extra WHERE conditions for the search ``filter`` param.

    ``generic`` = unbranded USDA whole foods; ``branded`` = anything carrying a brand;
    ``mine`` = user-created foods — the caller's own plus legacy rows created before ownership
    existed (``created_by IS NULL``, visible to everyone on a shared personal-suite server).
    """
    if search_filter == "generic":
        return [Food.source == "usda", Food.brand.is_(None)]
    if search_filter == "branded":
        return [Food.brand.is_not(None)]
    if search_filter == "mine":
        owner = Food.created_by.is_(None)
        if user_id is not None:
            owner = or_(Food.created_by == user_id, owner)
        return [Food.source == "user", owner]
    return []


def _local_where(query: str, search_filter: str = "all", user_id=None):
    """Case-insensitive **token-AND** match on the cached ``foods`` table.

    Each whitespace-separated query word must appear somewhere in the name, in any order — so
    "ground turkey" matches "Turkey, ground, raw" (a plain contiguous ``%ground turkey%`` substring
    would miss it). Recipe-ingredient foods (``source='spoonacular'``) are excluded — they carry
    recipe-specific per-serving nutrition and would be confusing as standalone hits.
    """
    tokens = [tok for tok in re.split(r"\s+", query.strip()) if tok] or [query.strip()]
    return and_(
        Food.source != "spoonacular",
        *[Food.name.ilike(f"%{tok}%") for tok in tokens],
        *_filter_conditions(search_filter, user_id),
    )


async def _search_local(
    db: AsyncSession, query: str, limit: int, search_filter: str = "all", user_id=None
) -> list[Food]:
    """Candidate cached foods matching every query token. Returned unranked (the caller ranks by
    relevance + recency); a generous fetch cap keeps a strong match from being cut before ranking."""
    result = await db.execute(
        select(Food)
        .where(_local_where(query, search_filter, user_id))
        .order_by(Food.name)
        .limit(max(limit * 5, 100))
    )
    return list(result.scalars().all())


async def _search_local_fuzzy(
    db: AsyncSession, query: str, search_filter: str = "all", user_id=None
) -> dict:
    """Trigram-similar cached foods for a query the strict token-AND match barely served.

    Returns ``{food_id: (food, similarity)}``. The ``%`` operator rides the GIN trgm index and
    applies pg_trgm's similarity threshold, so "chiken breast" still finds "Chicken breast,
    raw" despite the typo. Only consulted when the strict match is thin — a well-served query
    never pays for the fuzzy scan.
    """
    sim = func.similarity(Food.name, query)
    result = await db.execute(
        select(Food, sim)
        .where(
            Food.source != "spoonacular",
            Food.name.op("%")(query),
            *_filter_conditions(search_filter, user_id),
        )
        .order_by(sim.desc())
        .limit(_FUZZY_LIMIT)
    )
    return {food.id: (food, similarity) for food, similarity in result.all()}


def _build_live_sources(client: httpx.AsyncClient, search_filter: str = "all") -> list[FoodSource]:
    """Construct the external sources in priority order (USDA before OFF).

    USDA is included only when an API key is configured — without it the FDC API rejects calls,
    so we skip it rather than waste a round-trip. The search filter narrows the fan-out:
    ``generic`` asks USDA for whole-food datasets only (OFF is all packaged goods, skipped);
    ``branded`` asks USDA for Branded plus OFF.
    """
    sources: list[FoodSource] = []
    if settings.usda_api_key:
        data_type = {
            "generic": "Foundation,SR Legacy",
            "branded": "Branded",
        }.get(search_filter)
        if data_type is not None:
            sources.append(
                UsdaFoodSource(
                    client, settings.usda_api_key, settings.usda_base_url, data_type=data_type
                )
            )
        else:
            sources.append(UsdaFoodSource(client, settings.usda_api_key, settings.usda_base_url))
    if search_filter != "generic":
        sources.append(OpenFoodFactsSource(client, settings.off_base_url, settings.off_user_agent))
    return sources


async def _gather(
    sources: list[FoodSource], query: str, limit: int
) -> tuple[list[NormalizedFood], int]:
    """Search each source in order; a failing source is logged and skipped, not fatal.

    Returns the combined items plus how many sources succeeded — the caller only marks the
    query's external fetch "done" when at least one source actually answered, so an outage
    doesn't suppress retries for the whole TTL.
    """
    items: list[NormalizedFood] = []
    ok = 0
    for source in sources:
        try:
            items.extend(await source.search(query, limit=limit))
            ok += 1
        except Exception as exc:  # noqa: BLE001 — one bad source shouldn't sink the whole search
            log.warning("Food source %s failed for %r: %s", source.source_tag, query, exc)
    return items, ok


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


async def _attach_missing_portions(db: AsyncSession, existing: Food, item: NormalizedFood) -> bool:
    """Give an already-cached, portion-less row the portions a fresh external result carries.

    This is how foods cached before the portions feature (or before an external re-fetch)
    gradually heal — an existing row's portions are never overwritten, only filled in when
    absent. Returns True when rows were added.
    """
    if not item.portions:
        return False
    has_portions = (
        await db.execute(select(FoodPortion.id).where(FoodPortion.food_id == existing.id).limit(1))
    ).first() is not None
    if has_portions:
        return False
    for portion in item.portions:
        db.add(FoodPortion(food_id=existing.id, **portion.model_dump()))
    return True


async def cache_foods(db: AsyncSession, items: list[NormalizedFood]) -> list[Food]:
    """Persist new normalized foods, collapsing duplicates within the batch and against the DB.

    Deduplication is by barcode and by normalized name (CLAUDE.md §5). Existing rows are reused
    so repeated searches don't grow the table; a reused row with no portions picks up the fresh
    result's portions. New foods persist their portions as child rows.
    """
    results: list[Food] = []
    seen: set[str] = set()
    new_rows: list[Food] = []
    dirty = False
    for item in items:
        key = item.dedup_key()
        if key in seen:
            continue
        seen.add(key)
        existing = await _find_existing(db, item)
        if existing is not None:
            dirty = await _attach_missing_portions(db, existing, item) or dirty
            results.append(existing)
            continue
        food = Food(**item.to_food_kwargs())
        # Assigning on a transient object doesn't trigger the lazy="raise" loader.
        food.portions = [FoodPortion(**p.model_dump()) for p in item.portions]
        db.add(food)
        new_rows.append(food)
        results.append(food)

    if new_rows or dirty:
        await db.commit()
        for food in new_rows:
            await db.refresh(food)
    return results


def _query_ttl_key(query: str, search_filter: str) -> str:
    """The ``search_queries`` primary key for a query + filter combination.

    Filters fan out to different sources ("generic" never asks OFF), so a filtered fetch must
    not mark the unfiltered query as done — non-default filters get their own keyspace.
    """
    key = normalized_name(query)
    if search_filter != "all":
        key = f"{search_filter}:{key}"
    return key[:255]


async def _query_recently_fetched(db: AsyncSession, ttl_key: str) -> bool:
    row = await db.get(SearchQuery, ttl_key)
    if row is None:
        return False
    age = datetime.datetime.now(datetime.timezone.utc) - row.last_fetched_at
    return age < datetime.timedelta(hours=settings.external_refetch_ttl_hours)


async def _mark_query_fetched(db: AsyncSession, ttl_key: str) -> None:
    now = datetime.datetime.now(datetime.timezone.utc)
    await db.execute(
        pg_insert(SearchQuery)
        .values(normalized_query=ttl_key, last_fetched_at=now)
        .on_conflict_do_update(
            index_elements=[SearchQuery.normalized_query], set_={"last_fetched_at": now}
        )
    )
    await db.commit()


async def _refresh_from_external(
    query: str,
    limit: int,
    search_filter: str = "all",
    *,
    sources: list[FoodSource] | None = None,
) -> None:
    """Fetch + cache external results for ``query`` on its own DB session and HTTP client.

    Runs as a background task after a stale-but-well-served search already returned the local
    page — the user never waits on the 8 s external timeout for a warm query. A lost task (e.g.
    shutdown mid-flight) is harmless: the query just stays stale and refetches next time.
    ``sources`` is injectable for tests.
    """
    from app.database import AsyncSessionLocal  # local import to avoid a cycle at module load

    try:
        if sources is not None:
            items, ok = await _gather(sources, query, limit)
        else:
            async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
                items, ok = await _gather(_build_live_sources(client, search_filter), query, limit)
        async with AsyncSessionLocal() as db:
            await cache_foods(db, items)
            if ok:
                await _mark_query_fetched(db, _query_ttl_key(query, search_filter))
    except Exception as exc:  # noqa: BLE001 — background refresh must never propagate
        log.warning("Background food refresh failed for %r: %s", query, exc)


async def search_foods(
    db: AsyncSession,
    query: str,
    *,
    user_id=None,
    sources: list[FoodSource] | None = None,
    search_filter: str = "all",
    background_tasks: BackgroundTasks | None = None,
) -> list[Food]:
    """Local-first food search that keeps enriching from external sources on a per-query TTL.

    Flow:

    1. Strict token-AND match against the local cache (plus the ``filter`` conditions). When
       that comes up thin, union in trigram-fuzzy matches so typos still find cached foods.
    2. ``filter="mine"`` or no external source available → return the ranked local page.
    3. External fetch for this query still fresh (``search_queries`` TTL) → ranked local page,
       zero network. This replaces the old "full local page → never fetch again" shortcut: the
       cache can't permanently shadow richer external data anymore.
    4. Stale + healthy local page (``>= external_supplement_threshold``) → return the local
       page **now** and refresh from USDA/OFF in the background.
    5. Stale + thin local page → block on the fan-out, cache, merge, rank (first-time queries
       still surface external results). The fetch is marked done only if a source succeeded.

    ``sources`` is injectable for tests and applies to the blocking path; in production it's
    ``None`` and live USDA/OFF sources are built on demand (only when
    :data:`settings.food_search_live` is enabled).
    """
    q = query.strip()
    if not q:
        return []

    limit = settings.external_search_limit
    recent_rank = await _recent_food_rank(db, user_id) if user_id is not None else {}
    local = await _search_local(db, q, limit, search_filter, user_id)

    similarity: dict = {}
    if len(local) < settings.external_supplement_threshold:
        seen_ids = {f.id for f in local}
        for food_id, (food, sim) in (
            await _search_local_fuzzy(db, q, search_filter, user_id)
        ).items():
            similarity[food_id] = sim
            if food_id not in seen_ids:
                local.append(food)

    def local_page() -> list[Food]:
        return rank_foods(local, q, recent_rank, similarity)[:limit]

    # "My foods" is a purely local namespace; external sources can never serve it.
    if search_filter == "mine":
        return local_page()
    if sources is None and not settings.food_search_live:
        return local_page()

    ttl_key = _query_ttl_key(q, search_filter)
    if await _query_recently_fetched(db, ttl_key):
        return local_page()

    if len(local) >= settings.external_supplement_threshold:
        # Well-served locally: don't make the user wait on the external timeout — hand the
        # refresh to a background task (or fire-and-forget outside a request context).
        if sources is None:
            if background_tasks is not None:
                background_tasks.add_task(_refresh_from_external, q, limit, search_filter)
            else:
                asyncio.get_running_loop().create_task(
                    _refresh_from_external(q, limit, search_filter)
                )
        return local_page()

    if sources is not None:
        items, ok = await _gather(sources, q, limit)
    else:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
            items, ok = await _gather(_build_live_sources(client, search_filter), q, limit)

    cached = await cache_foods(db, items)
    if ok:
        await _mark_query_fetched(db, ttl_key)

    # Merge local + external (deduped by id), then rank the whole set by relevance so the literal
    # food outranks a branded item that only mentions the query in a long description — external
    # sources return their own order, which is otherwise what a first-time query would show.
    merged = list(local)
    seen = {f.id for f in local}
    for food in cached:
        if food.id not in seen and _matches_filter(food, search_filter):
            seen.add(food.id)
            merged.append(food)
    return rank_foods(merged, q, recent_rank, similarity)[:limit]


def _matches_filter(food: Food, search_filter: str) -> bool:
    """Python-side mirror of :func:`_filter_conditions` for just-fetched external rows.

    The narrowed source fan-out mostly guarantees this already, but e.g. an OFF product with no
    brand would otherwise leak into a "branded" result page.
    """
    if search_filter == "generic":
        return food.source == "usda" and food.brand is None
    if search_filter == "branded":
        return food.brand is not None
    return True


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


async def create_custom_food(db: AsyncSession, data: dict, *, user_id=None) -> Food:
    """Insert a user-defined food (``source='user'``), owned by ``user_id`` when given."""
    food = Food(source="user", created_by=user_id, **data)
    db.add(food)
    await db.commit()
    await db.refresh(food)
    return food


async def get_food(db: AsyncSession, food_id) -> Food | None:
    return await db.get(Food, food_id)


async def _select_food_with_portions(db: AsyncSession, food_id) -> Food | None:
    # populate_existing: after the lazy-enrichment commit the food is already in the session's
    # identity map with an empty (pre-insert) portions collection — a plain re-select would
    # return that stale object untouched instead of loading the new child rows.
    result = await db.execute(
        select(Food)
        .options(selectinload(Food.portions))
        .where(Food.id == food_id)
        .execution_options(populate_existing=True)
    )
    return result.scalars().first()


async def get_food_detail(
    db: AsyncSession, food_id, *, usda_source: UsdaFoodSource | None = None
) -> Food | None:
    """One food with its portions eagerly loaded — the only sanctioned way to serialize
    :class:`~app.schemas.food.FoodDetailOut` (``Food.portions`` is ``lazy="raise"``).

    Lazy USDA enrichment: FDC *search* responses never carry ``foodPortions``, so a cached
    USDA food gets its household measures fetched from the FDC detail endpoint here — once per
    food, on its first detail request (a food tap, never a keystroke). ``portions_fetched_at``
    is stamped on success (even an empty result — FDC had nothing more for this food) and left
    NULL on a transport failure so a later tap retries. ``usda_source`` is injectable for
    tests; in production the live source is built on demand.
    """
    food = await _select_food_with_portions(db, food_id)
    if food is None:
        return None

    wants_enrichment = (
        food.source == "usda"
        and food.source_id is not None
        and food.portions_fetched_at is None
        and not food.portions
    )
    if not wants_enrichment:
        return food
    if usda_source is None and not (settings.food_search_live and settings.usda_api_key):
        return food

    try:
        if usda_source is not None:
            portions = await usda_source.fetch_portions(food.source_id)
        else:
            async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
                live = UsdaFoodSource(client, settings.usda_api_key, settings.usda_base_url)
                portions = await live.fetch_portions(food.source_id)
    except Exception as exc:  # noqa: BLE001 — enrichment is best-effort; the food still serves
        log.warning("USDA portion fetch failed for %s: %s", food.source_id, exc)
        return food

    for portion in portions:
        db.add(FoodPortion(food_id=food.id, **portion.model_dump()))
    food.portions_fetched_at = datetime.datetime.now(datetime.timezone.utc)
    await db.commit()
    return await _select_food_with_portions(db, food_id)

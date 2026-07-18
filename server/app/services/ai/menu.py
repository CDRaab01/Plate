"""Menu-link parsing: fetched menu text → structured parse → resolved component draft.

Mirrors :mod:`app.services.ai.voice`: an LM Studio structured parse
(:mod:`app.services.ai.menu_prompts`), then each component either carries the menu's **official**
nutrition verbatim or resolves its generic ``search_term`` against the **trusted food search**.
The result is the shared editable-draft rule (CLAUDE.md §3): nothing is written to the DB — the
user reviews the components in the restaurant editor and saves via the ordinary POST /restaurants.

``client``/``fetch``/``search`` are injectable so tests never reach LM Studio or the network.
"""

import contextlib
import uuid
from collections.abc import Awaitable, Callable

import httpx
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.food import Food
from app.schemas.restaurant import ComponentMacrosIn, MenuParseComponent, MenuParseResponse
from app.services.ai.menu_prompts import build_menu_messages, parse_menu_components
from app.services.ai.vision import _complete  # shared LM Studio completion + error taxonomy
from app.services.ai.voice import SearchFn, _portion_grams
from app.services.food_service import search_foods
from app.services.menu_fetch import fetch_menu_text

FetchFn = Callable[[str], Awaitable[str]]

# Confidence tiers: official numbers are the menu's own; a resolved generic is the voice-path
# estimate; an unresolved term leaves a name-only stub the user must link.
_OFFICIAL_CONFIDENCE = 0.9
_RESOLVED_CONFIDENCE = 0.7
_UNRESOLVED_CONFIDENCE = 0.2

_NOTHING_NOTE = "Couldn't find build-your-own components on that menu. Add them manually instead."
_LOW_CONF_NOTE = "Some components couldn't be matched — review them before saving."


async def parse_menu(
    url: str,
    db: AsyncSession,
    user_id: uuid.UUID,
    *,
    client: httpx.AsyncClient | None = None,
    fetch: FetchFn | None = None,
    search: SearchFn | None = None,
) -> MenuParseResponse:
    """Fetch + parse a menu URL into an editable component draft (never persisted here)."""
    text = await (fetch or fetch_menu_text)(url)

    messages = build_menu_messages(text)
    async with contextlib.AsyncExitStack() as stack:
        if client is None:
            client = await stack.enter_async_context(
                httpx.AsyncClient(timeout=settings.lm_studio_timeout)
            )
        raw_reply = await _complete(client, messages)

    restaurant_name, parsed = parse_menu_components(raw_reply)
    if not parsed:
        return MenuParseResponse(
            restaurant_name=restaurant_name,
            menu_url=url,
            components=[],
            low_confidence=True,
            note=_NOTHING_NOTE,
        )

    components: list[MenuParseComponent] = []
    for raw in parsed:
        if raw["official"] is not None:
            components.append(_official_component(raw))
        else:
            match = await _resolve(db, raw["search_term"] or raw["name"], user_id, search)
            components.append(_estimate_component(raw, match))

    low = any(c.confidence <= settings.photo_low_confidence_threshold for c in components)
    return MenuParseResponse(
        restaurant_name=restaurant_name,
        menu_url=url,
        components=components,
        low_confidence=low,
        note=_LOW_CONF_NOTE if low else None,
    )


async def _resolve(
    db: AsyncSession, term: str, user_id: uuid.UUID, search: SearchFn | None
) -> Food | None:
    """Top trusted-search hit for a component's generic term, or None when nothing matches."""
    if search is not None:
        results = await search(db, term)
    else:
        results = await search_foods(db, term, user_id=user_id)
    return results[0] if results else None


def _official_component(raw: dict) -> MenuParseComponent:
    """Carry the menu's published numbers through verbatim (one component = one menu serving)."""
    official = raw["official"]
    macros = ComponentMacrosIn(**official)
    return MenuParseComponent(
        category=raw["category"],
        name=raw["name"],
        source="official",
        macros=macros,
        quantity=1.0,
        unit="serving",
        kcal=macros.kcal,
        protein_g=macros.protein_g,
        carbs_g=macros.carbs_g,
        fat_g=macros.fat_g,
        confidence=_OFFICIAL_CONFIDENCE,
    )


def _estimate_component(raw: dict, match: Food | None) -> MenuParseComponent:
    """An estimate row: real macros from the resolved generic food, else a name-only stub."""
    if match is None:
        return MenuParseComponent(
            category=raw["category"],
            name=raw["name"],
            source="estimate",
            quantity=raw["typical_grams"] or 100.0,
            unit="g",
            kcal=0.0,
            protein_g=0.0,
            carbs_g=0.0,
            fat_g=0.0,
            confidence=_UNRESOLVED_CONFIDENCE,
        )

    grams = raw["typical_grams"] or _portion_grams(match, 1.0, "serving")
    factor = grams / 100.0
    return MenuParseComponent(
        category=raw["category"],
        name=raw["name"],
        source="estimate",
        food_id=match.id,
        food_name=match.name,
        quantity=grams,
        unit="g",
        kcal=(match.kcal_per_100g or 0.0) * factor,
        protein_g=(match.protein_g_per_100g or 0.0) * factor,
        carbs_g=(match.carbs_g_per_100g or 0.0) * factor,
        fat_g=(match.fat_g_per_100g or 0.0) * factor,
        confidence=_RESOLVED_CONFIDENCE,
    )

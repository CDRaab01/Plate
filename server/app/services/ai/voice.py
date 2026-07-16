"""Voice logging: recognized text → structured parse → foods → editable draft (CLAUDE.md §3, §6).

The client does speech→text on-device and POSTs only the text. Here we run a structured LM Studio
parse (:mod:`app.services.ai.voice_prompts`), then resolve each spoken food against the **trusted
food search** to attach real macros, and return the shared editable-draft response
(:class:`~app.schemas.photo.PhotoEstimateResponse`). Nothing is written to the DB — the user confirms
each item in the same draft editor the photo path uses (never auto-committed).

``client`` is injectable so tests drive a mocked transport and CI never reaches a real LM Studio.
``search`` is injectable so tests resolve foods without the network.
"""

import contextlib
import uuid
from collections.abc import Awaitable, Callable

import httpx
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.food import Food
from app.nutrition.units import oz_to_g
from app.schemas.photo import PhotoEstimateItem, PhotoEstimateResponse
from app.services.ai.vision import _complete  # shared LM Studio completion + error taxonomy
from app.services.ai.voice_prompts import build_voice_messages, parse_spoken_items
from app.services.food_service import search_foods

# Portion (grams) assumed for a "serving" of a resolved food that carries no serving size — enough to
# produce sensible macros while the low-confidence flag prompts the user to adjust before logging.
_DEFAULT_SERVING_G = 100.0

# Confidence for a resolved food (real macros) vs. an unresolved one (name only, user must fill in).
_RESOLVED_CONFIDENCE = 0.7
_UNRESOLVED_CONFIDENCE = 0.2

_NOTHING_NOTE = "Couldn't make out any foods. Try again, or add them from search."
_LOW_CONF_NOTE = "Some items couldn't be matched — check the amounts and macros before logging."

SearchFn = Callable[[AsyncSession, str], Awaitable[list[Food]]]


async def parse_voice_log(
    text: str,
    db: AsyncSession,
    user_id: uuid.UUID,
    *,
    client: httpx.AsyncClient | None = None,
    search: SearchFn | None = None,
) -> PhotoEstimateResponse:
    """Parse spoken text into an editable food draft (never logged here)."""
    messages = build_voice_messages(text)
    async with contextlib.AsyncExitStack() as stack:
        if client is None:
            client = await stack.enter_async_context(
                httpx.AsyncClient(timeout=settings.lm_studio_timeout)
            )
        raw_reply = await _complete(client, messages)

    parsed = parse_spoken_items(raw_reply)
    if not parsed:
        return PhotoEstimateResponse(items=[], low_confidence=True, note=_NOTHING_NOTE)

    items: list[PhotoEstimateItem] = []
    for spoken in parsed:
        match = await _resolve(db, spoken["food"], user_id, search)
        items.append(_to_item(spoken, match))

    low = any(item.confidence <= settings.photo_low_confidence_threshold for item in items)
    note = _LOW_CONF_NOTE if low else None
    return PhotoEstimateResponse(items=items, low_confidence=low, note=note)


async def _resolve(
    db: AsyncSession, food: str, user_id: uuid.UUID, search: SearchFn | None
) -> Food | None:
    """Top food-search hit for a spoken name, or ``None`` when nothing matches."""
    if search is not None:
        results = await search(db, food)
    else:
        results = await search_foods(db, food, user_id=user_id)
    return results[0] if results else None


def _to_item(spoken: dict, match: Food | None) -> PhotoEstimateItem:
    """Build one draft item: real macros for the spoken portion when resolved, else a name-only stub."""
    quantity = spoken["quantity"]
    unit = spoken["unit"]

    if match is None:
        # Unresolved: keep the food in the draft so it isn't silently dropped; the user fills in the
        # macros (low confidence flags it).
        return PhotoEstimateItem(
            name=spoken["food"],
            est_grams=0.0,
            kcal=0.0,
            protein_g=0.0,
            carbs_g=0.0,
            fat_g=0.0,
            confidence=_UNRESOLVED_CONFIDENCE,
        )

    grams = _portion_grams(match, quantity, unit)
    factor = grams / 100.0
    return PhotoEstimateItem(
        name=match.name,
        est_grams=grams,
        kcal=(match.kcal_per_100g or 0.0) * factor,
        protein_g=(match.protein_g_per_100g or 0.0) * factor,
        carbs_g=(match.carbs_g_per_100g or 0.0) * factor,
        fat_g=(match.fat_g_per_100g or 0.0) * factor,
        confidence=_RESOLVED_CONFIDENCE,
    )


def _portion_grams(match: Food, quantity: float, unit: str) -> float:
    """Convert a spoken quantity+unit to grams for the resolved food.

    ``g``/``oz`` are direct; a ``serving`` (or any count like "two eggs") uses the food's serving size
    in grams when known, else a nominal default so macros still populate.
    """
    if unit == "g":
        return quantity
    if unit == "oz":
        return oz_to_g(quantity)
    serving_g = (
        match.serving_size if match.serving_size and match.serving_size > 0 else _DEFAULT_SERVING_G
    )
    return quantity * serving_g

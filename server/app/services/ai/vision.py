"""Photo → macro estimation against the LM Studio vision model (CLAUDE.md §3, §6).

Mirrors the coach client (:mod:`app.services.ai.client`): build the message list, call LM Studio's
OpenAI-compatible ``/chat/completions``, and map transport/HTTP failures to clean HTTP errors. The
difference is the payload — a meal photo encoded as a base64 data URL — and the output, which is
parsed into an **editable draft** the user must confirm. Nothing here writes to the database; the
estimate is never auto-committed (CLAUDE.md §3).

``client`` is injectable so tests drive a mocked transport and CI never reaches a real server.
"""
import base64
import contextlib
import logging

import httpx
from fastapi import HTTPException, status

from app.config import settings
from app.schemas.photo import PhotoEstimateItem, PhotoEstimateResponse
from app.services.ai.photo_prompts import build_vision_messages, parse_estimate

log = logging.getLogger(__name__)

_NO_FOOD_NOTE = (
    "Couldn't identify the food in this photo. Try a clearer, closer shot — or search for it "
    "manually."
)


def _data_url(image_bytes: bytes, content_type: str) -> str:
    """Encode the upload as a base64 ``data:`` URL for the model's ``image_url`` content part."""
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


async def estimate_photo(
    image_bytes: bytes,
    content_type: str,
    *,
    client: httpx.AsyncClient | None = None,
) -> PhotoEstimateResponse:
    """Send a meal photo to the vision model and return an editable draft of estimated foods.

    Transport/HTTP failures surface as HTTP errors (the model is unreachable / erroring). Unusable
    *content* — the model returned junk or saw no food — degrades to an empty, low-confidence draft
    with a note, so the user is guided to retake or search rather than shown an error.
    """
    messages = build_vision_messages(_data_url(image_bytes, content_type))

    async with contextlib.AsyncExitStack() as stack:
        if client is None:
            client = await stack.enter_async_context(
                httpx.AsyncClient(timeout=settings.lm_studio_timeout)
            )
        raw_reply = await _complete(client, messages)

    items = [PhotoEstimateItem(**item) for item in parse_estimate(raw_reply)]
    return _to_response(items)


def _to_response(items: list[PhotoEstimateItem]) -> PhotoEstimateResponse:
    """Wrap parsed items with the low-confidence flag + a helpful note when there's nothing usable."""
    if not items:
        return PhotoEstimateResponse(items=[], low_confidence=True, note=_NO_FOOD_NOTE)

    threshold = settings.photo_low_confidence_threshold
    low = any(item.confidence <= threshold for item in items)
    note = (
        "Some items are low-confidence — double-check the amounts before logging."
        if low
        else None
    )
    return PhotoEstimateResponse(items=items, low_confidence=low, note=note)


async def _complete(client: httpx.AsyncClient, messages: list[dict]) -> str:
    """POST the vision completion and pull the assistant text out, mapping failures to HTTP errors.

    Shares the coach client's error taxonomy: 502 for an erroring/malformed server, 504 for a
    timeout (often a cold-start model load), 503 when LM Studio is unreachable.
    """
    try:
        resp = await client.post(
            f"{settings.lm_studio_base_url}/chat/completions",
            json={
                "model": settings.lm_studio_vision_model,
                "messages": messages,
                # Low temperature: we want a faithful read of the photo, not creativity.
                "temperature": 0.2,
            },
        )
        resp.raise_for_status()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"LM Studio returned {exc.response.status_code}",
        ) from exc
    except httpx.TimeoutException as exc:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="Photo analysis timed out — the model may still be loading. Try again.",
        ) from exc
    except httpx.RequestError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Photo analysis is not reachable. Is LM Studio running?",
        ) from exc

    try:
        data = resp.json()
        reply = data["choices"][0]["message"]["content"]
        if not isinstance(reply, str):
            raise TypeError("content is not a string")
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Photo analysis returned a malformed response.",
        ) from exc
    return reply

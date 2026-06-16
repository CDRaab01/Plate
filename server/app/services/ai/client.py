"""AI coach inference against LM Studio (CLAUDE.md §2, §6).

Mirrors Spotter's chat client: validate the user's turns, build the message list (system prompt +
trusted macro context + history), call LM Studio's OpenAI-compatible ``/chat/completions``, and map
transport/HTTP failures to clean HTTP errors. Local-only — there is no hosted fallback.

``client`` is injectable so tests drive a mocked transport and CI never reaches a real server
(CLAUDE.md §10); in production it's ``None`` and a client is built per call.
"""
import contextlib
import datetime
import logging
import uuid

import httpx
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.schemas.ai import ChatRequest, ChatResponse
from app.services.ai.context_service import build_macro_context
from app.services.ai.prompts import build_messages, validate_request, validate_response

log = logging.getLogger(__name__)


async def chat(
    req: ChatRequest,
    db: AsyncSession,
    user_id: uuid.UUID,
    *,
    day: datetime.date | None = None,
    trained: bool = False,
    client: httpx.AsyncClient | None = None,
) -> ChatResponse:
    # Guard every user turn, not just the latest — injection can hide in earlier history.
    for msg in req.messages:
        if msg.role == "user":
            error = validate_request(msg.content)
            if error:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
                )

    last_user = next(
        (m.content for m in reversed(req.messages) if m.role == "user"), ""
    )
    if not last_user.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Send a message for the coach to answer.",
        )

    history = [m.model_dump() for m in req.messages[:-1]]
    macro_context = await build_macro_context(
        db, user_id, day or datetime.date.today(), trained=trained
    )
    messages = build_messages(history, last_user, macro_context)

    # Reuse an injected client (tests) or open one per call (production).
    async with contextlib.AsyncExitStack() as stack:
        if client is None:
            client = await stack.enter_async_context(
                httpx.AsyncClient(timeout=settings.lm_studio_timeout)
            )
        raw_reply = await _complete(client, messages)

    return ChatResponse(reply=validate_response(raw_reply) or _fallback_reply())


async def _complete(client: httpx.AsyncClient, messages: list[dict]) -> str:
    """POST the chat completion and pull the assistant text out, mapping failures to HTTP errors."""
    try:
        resp = await client.post(
            f"{settings.lm_studio_base_url}/chat/completions",
            json={
                "model": settings.lm_studio_model,
                "messages": messages,
                "temperature": 0.7,
            },
        )
        resp.raise_for_status()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"LM Studio returned {exc.response.status_code}",
        ) from exc
    except httpx.TimeoutException as exc:
        # Distinct from "unreachable": LM Studio answered but didn't finish in time (commonly a
        # cold-start model load on the first request).
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="The coach timed out — the model may still be loading. Try again.",
        ) from exc
    except httpx.RequestError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="The coach is not reachable. Is LM Studio running?",
        ) from exc

    try:
        data = resp.json()
        reply = data["choices"][0]["message"]["content"]
        if not isinstance(reply, str):
            raise TypeError("content is not a string")
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The coach returned a malformed response.",
        ) from exc
    return reply


def _fallback_reply() -> str:
    """Shown if the model returns only whitespace (or nothing useful after scrubbing)."""
    return "I didn't catch that — could you rephrase your question about food or your macros?"

"""AI coach chat (CLAUDE.md §6).

``POST /ai/chat`` — send the conversation so far, get the coach's reply. The coach reasons over the
user's remaining macros + goal, derived server-side. Inference runs against LM Studio (Gemma 3);
the route is rate-limited since each call hits the model.
"""
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.schemas.ai import ChatRequest, ChatResponse
from app.security import CurrentUser
from app.services.ai.client import chat

router = APIRouter(prefix="/ai", tags=["ai"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.post("/chat", response_model=ChatResponse)
@limiter.limit("20/minute")
async def coach_chat(
    request: Request,
    req: ChatRequest,
    current_user: CurrentUser,
    db: DbSession,
):
    return await chat(req, db, current_user.id)

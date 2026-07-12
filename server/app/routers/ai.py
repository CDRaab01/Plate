"""AI coach chat (CLAUDE.md §6).

``POST /ai/chat`` — send the conversation so far, get the coach's reply. The coach reasons over the
user's remaining macros + goal, derived server-side. Inference runs against LM Studio (Gemma 3);
the route is rate-limited since each call hits the model.
"""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.schemas.ai import ChatRequest, ChatResponse
from app.security import CurrentUser
from app.services.ai.client import chat
from app.services.plan_source import PlanSource, get_plan_source, planned_meals
from app.services.workout_source import (
    WorkoutSource,
    get_workout_source,
    is_training_day,
    training_week,
)

router = APIRouter(prefix="/ai", tags=["ai"])

DbSession = Annotated[AsyncSession, Depends(get_db)]
Workouts = Annotated[WorkoutSource, Depends(get_workout_source)]
Plans = Annotated[PlanSource, Depends(get_plan_source)]


@router.post("/chat", response_model=ChatResponse)
@limiter.limit("20/minute")
async def coach_chat(
    request: Request,
    req: ChatRequest,
    current_user: CurrentUser,
    db: DbSession,
    workouts: Workouts,
    plans: Plans,
):
    # Tell the coach if the user trained today (Spotter-awareness, §7), how the training WEEK looks
    # (Link B), and what meals are planned today (Cookbook, Link E) so its framing matches the
    # bumped targets, the week's rhythm, and tonight's plan. All best-effort: a sister-app outage
    # just means that framing is absent.
    today = datetime.date.today()
    trained = await is_training_day(current_user.email, today, source=workouts)
    week = await training_week(current_user.email, today, source=workouts)
    plan = await planned_meals(current_user.email, today, source=plans)
    return await chat(req, db, current_user.id, trained=trained, week=week, plan=plan)

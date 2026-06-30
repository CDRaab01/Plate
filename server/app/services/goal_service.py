"""Goals + computed targets (CLAUDE.md §7).

The active goal is the most recent ``user_goals`` row; setting a goal appends a new one so the
history stays intact for later trend/coach context. Targets are **computed on read** — primarily a
function of the active goal, but the *weight* fed into the engine is the user's latest weigh-in on
or before the day (:func:`app.services.metric_service.latest_weight_kg_on_or_before`) when one
exists, so a cut's deficit tracks current bodyweight instead of the static goal value; absent any
weigh-in it falls back to the goal's ``weight_kg``. Targets are deliberately not snapshotted (the
``daily_targets`` table stays unused) — a stored snapshot would defeat that adaptiveness.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user_goal import UserGoal
from app.nutrition.targets import Targets, compute_targets, from_goal
from app.schemas.goal import GoalUpsert
from app.services.metric_service import latest_weight_kg_on_or_before


async def set_goal(db: AsyncSession, user_id: uuid.UUID, req: GoalUpsert) -> UserGoal:
    """Append a new goal row for the user and return it (now the active goal)."""
    goal = UserGoal(user_id=user_id, **req.model_dump())
    db.add(goal)
    await db.commit()
    await db.refresh(goal)
    return goal


async def get_active_goal(db: AsyncSession, user_id: uuid.UUID) -> UserGoal | None:
    """The user's current goal — the most recently created row, or ``None`` if they've set none."""
    result = await db.execute(
        select(UserGoal)
        .where(UserGoal.user_id == user_id)
        .order_by(UserGoal.created_at.desc())
        .limit(1)
    )
    return result.scalars().first()


async def compute_targets_for(
    db: AsyncSession,
    user_id: uuid.UUID,
    day: datetime.date,
    *,
    trained: bool = False,
) -> Targets | None:
    """Compute the user's targets for ``day`` from their active goal, or ``None`` if no goal is set.

    When ``trained`` is true (Spotter reported a workout for ``day``), the targets carry the
    training-day bump. Callers decide ``trained`` via :func:`app.services.workout_source.is_training_day`
    and pass it in, keeping this function a pure read of the goal + the bump flag (no network here).
    """
    goal = await get_active_goal(db, user_id)
    if goal is None:
        return None
    # Prefer the latest weigh-in on/before the day; None falls back to the goal's stored weight.
    weight_kg = await latest_weight_kg_on_or_before(db, user_id, day)
    return compute_targets(from_goal(goal, weight_kg=weight_kg), trained=trained)

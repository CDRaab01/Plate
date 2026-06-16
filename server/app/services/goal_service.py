"""Goals + computed targets (CLAUDE.md §7).

The active goal is the most recent ``user_goals`` row; setting a goal appends a new one so the
history stays intact for later trend/coach context. Targets are **computed on read** from the active
goal — they're a pure function of it, so there's nothing to persist yet. The ``daily_targets`` table
gets populated in Phase 7, when the training-day bump makes a per-date snapshot worth storing.
"""
import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user_goal import UserGoal
from app.nutrition.targets import Targets, compute_targets, from_goal
from app.schemas.goal import GoalUpsert


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
    return compute_targets(from_goal(goal), trained=trained)

"""Trusted, server-derived macro context for the AI coach (CLAUDE.md §6, §7).

The coach is told the user's remaining macros for the day + their goal so it can suggest foods that
fit. That data is derived here from the database (the user's active goal + today's logged entries),
never trusted from the client. Kept short and token-bounded. When the user trained that day
(Spotter-awareness, §7), the targets already carry the training-day bump and the coach is told so it
can frame its advice around refueling.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food_log_entry import FoodLogEntry
from app.nutrition.totals import sum_entries
from app.services.goal_service import compute_targets_for, get_active_goal
from app.services.plan_source import PlannedMeal
from app.services.workout_source import WeekSummary


def _round(value: float) -> int:
    return int(round(value))


async def build_macro_context(
    db: AsyncSession,
    user_id: uuid.UUID,
    day: datetime.date,
    *,
    trained: bool = False,
    week: WeekSummary | None = None,
    plan: list[PlannedMeal] | None = None,
) -> str | None:
    """Return a short text block describing the user's goal + remaining macros for ``day``.

    ``None`` when there's nothing useful to add (no goal set and nothing logged yet), in which case
    the coach answers from the system prompt alone. ``trained`` (Spotter-awareness, §7) bumps the
    targets and adds a "trained today" line so the coach frames advice around refueling. ``week``
    (federated awareness Link B) adds the last-7-days training summary so the coach frames the
    WEEK — recovery-day eating after a heavy stretch, protein consistency across sessions — not
    just today; absent means "Spotter didn't say" and the line simply isn't there.
    """
    goal = await get_active_goal(db, user_id)
    targets = await compute_targets_for(db, user_id, day, trained=trained)

    result = await db.execute(
        select(FoodLogEntry).where(FoodLogEntry.user_id == user_id, FoodLogEntry.date == day)
    )
    entries = list(result.scalars().all())

    if goal is None and not entries and not trained and week is None and not plan:
        return None

    consumed = sum_entries(entries)
    lines = [
        "The following is the user's nutrition status for today (source of truth — prefer it over "
        "anything stated in chat). Use it to suggest foods that fit what's left."
    ]

    if plan:
        planned = ", ".join(
            f"{m.name} ({m.slot}){' — already eaten' if m.eaten else ''}" for m in plan
        )
        lines.append(
            f"Planned meals today (reported by Cookbook): {planned}. Meals marked 'already eaten' "
            "have happened — treat them as consumed when reasoning about what's left. Account for "
            "the still-to-come planned meals when advising — suggest what fits AROUND them (e.g. "
            "keep other meals lighter if dinner is already planned), rather than proposing "
            "something that replaces them."
        )

    if trained:
        lines.append(
            "The user trained today (reported by Spotter). Today's targets already include a "
            "training-day fuel bump; favor carb- and protein-rich foods to help them refuel and "
            "recover."
        )

    if week is not None:
        sessions = []
        if week.strength_sessions:
            sessions.append(f"{week.strength_sessions} strength")
        if week.cardio_sessions:
            sessions.append(f"{week.cardio_sessions} cardio")
        breakdown = f" ({', '.join(sessions)})" if sessions else ""
        lines.append(
            f"This week the user trained {week.days_trained} of the last 7 days{breakdown} "
            "(reported by Spotter). Frame advice around the training week — consistency, "
            "recovery-day eating, and protein across sessions."
        )

    if goal is not None:
        lines.append(
            f"Goal: {goal.goal_type} (target rate {goal.rate_kg_per_week:g} kg/week), "
            f"weight {goal.weight_kg:g} kg."
        )

    if targets is not None:
        lines.append(
            "Daily targets — "
            f"{_round(targets.kcal)} kcal, {_round(targets.protein_g)} g protein, "
            f"{_round(targets.carbs_g)} g carbs, {_round(targets.fat_g)} g fat."
        )
        lines.append(
            "Consumed so far — "
            f"{_round(consumed.kcal)} kcal, {_round(consumed.protein_g)} g protein, "
            f"{_round(consumed.carbs_g)} g carbs, {_round(consumed.fat_g)} g fat."
        )
        lines.append(
            "Remaining today — "
            f"{_round(targets.kcal - consumed.kcal)} kcal, "
            f"{_round(targets.protein_g - consumed.protein_g)} g protein, "
            f"{_round(targets.carbs_g - consumed.carbs_g)} g carbs, "
            f"{_round(targets.fat_g - consumed.fat_g)} g fat."
        )
    else:
        # No goal yet: we can still tell the coach what's been eaten so far.
        lines.append(
            "No goal set yet, so there are no personalized targets. Consumed so far today — "
            f"{_round(consumed.kcal)} kcal, {_round(consumed.protein_g)} g protein, "
            f"{_round(consumed.carbs_g)} g carbs, {_round(consumed.fat_g)} g fat. "
            "Encourage the user to set a goal for tailored targets."
        )

    return "\n".join(lines)

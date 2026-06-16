"""Trusted, server-derived macro context for the AI coach (CLAUDE.md §6, §7).

The coach is told the user's remaining macros for the day + their goal so it can suggest foods that
fit. That data is derived here from the database (the user's active goal + today's logged entries),
never trusted from the client. Kept short and token-bounded. The Spotter training-day bump layers in
during Phase 7; today this reads only Plate's own goal + log.
"""
import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.food_log_entry import FoodLogEntry
from app.nutrition.totals import sum_entries
from app.services.goal_service import compute_targets_for, get_active_goal


def _round(value: float) -> int:
    return int(round(value))


async def build_macro_context(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date
) -> str | None:
    """Return a short text block describing the user's goal + remaining macros for ``day``.

    ``None`` when there's nothing useful to add (no goal set and nothing logged yet), in which case
    the coach answers from the system prompt alone.
    """
    goal = await get_active_goal(db, user_id)
    targets = await compute_targets_for(db, user_id, day)

    result = await db.execute(
        select(FoodLogEntry).where(
            FoodLogEntry.user_id == user_id, FoodLogEntry.date == day
        )
    )
    entries = list(result.scalars().all())

    if goal is None and not entries:
        return None

    consumed = sum_entries(entries)
    lines = [
        "The following is the user's nutrition status for today (source of truth — prefer it over "
        "anything stated in chat). Use it to suggest foods that fit what's left."
    ]

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

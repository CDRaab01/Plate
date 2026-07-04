"""Per-user data export (ROADMAP T3 #6) — the honest "what if I leave the ecosystem" backstop.

Gathers every row the signed-in user owns into one JSON-serializable document. Columns are
serialized generically (introspection over ``__table__.columns``) so this keeps working as the
schema grows; secret columns on the user row are redacted. The shared ``foods`` reference cache is
not user-owned and is excluded — log entries snapshot their own nutrition, so the export stays
self-contained. Read-only, own-session auth.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.models.daily_target import DailyTarget
from app.models.food_log_entry import FoodLogEntry
from app.models.recipe import Recipe
from app.models.recipe_item import RecipeItem
from app.models.user_goal import UserGoal

EXPORT_SCHEMA_VERSION = 1

# Never leave the server in an export — auth secrets, not user content.
_USER_REDACT = frozenset({"hashed_password", "reset_token", "reset_token_expires_at"})


def _jsonify(value):
    if isinstance(value, (datetime.datetime, datetime.date)):
        return value.isoformat()
    if isinstance(value, uuid.UUID):
        return str(value)
    return value  # str/int/float/bool/None + JSON columns (already list/dict)


def _row(obj, *, exclude: frozenset = frozenset()) -> dict:
    return {
        c.name: _jsonify(getattr(obj, c.name))
        for c in obj.__table__.columns
        if c.name not in exclude
    }


async def _all(db: AsyncSession, stmt) -> list:
    return list((await db.execute(stmt)).scalars().all())


async def build_export(db: AsyncSession, user) -> dict:
    """Assemble the full export document for ``user``. Recipe items are fetched by parent id so the
    export is complete without relying on ORM relationships."""
    recipes = await _all(db, select(Recipe).where(Recipe.user_id == user.id))
    recipe_ids = [r.id for r in recipes]
    recipe_items = (
        await _all(db, select(RecipeItem).where(RecipeItem.recipe_id.in_(recipe_ids)))
        if recipe_ids
        else []
    )

    return {
        "app": "plate",
        "schema_version": EXPORT_SCHEMA_VERSION,
        "exported_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "user": _row(user, exclude=_USER_REDACT),
        "user_goals": [
            _row(g) for g in await _all(db, select(UserGoal).where(UserGoal.user_id == user.id))
        ],
        "body_metrics": [
            _row(m) for m in await _all(db, select(BodyMetric).where(BodyMetric.user_id == user.id))
        ],
        "daily_targets": [
            _row(t)
            for t in await _all(db, select(DailyTarget).where(DailyTarget.user_id == user.id))
        ],
        "food_log_entries": [
            _row(e)
            for e in await _all(db, select(FoodLogEntry).where(FoodLogEntry.user_id == user.id))
        ],
        "recipes": [_row(r) for r in recipes],
        "recipe_items": [_row(i) for i in recipe_items],
    }

"""User profile/settings helpers.

The ``users.settings`` column is a free-form JSON blob (stored as text). Today it holds exactly one
key — ``unit_system`` (the lb/kg + oz/g display preference). Reads tolerate a missing/blank/legacy
value and fall back to :data:`~app.nutrition.units.DEFAULT_UNIT_SYSTEM` (imperial), so existing rows
with ``settings = NULL`` behave as imperial without a data migration.
"""

import json
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.nutrition.units import DEFAULT_UNIT_SYSTEM, UNIT_SYSTEMS

log = logging.getLogger(__name__)


def _load_settings(user: User) -> dict:
    """Parse the user's settings JSON, tolerating NULL/blank/corrupt values."""
    raw = user.settings
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        log.warning("Ignoring unparseable settings JSON for user %s", user.id)
        return {}
    return data if isinstance(data, dict) else {}


def get_unit_system(user: User) -> str:
    """The user's unit_system, defaulting to imperial when unset or invalid."""
    value = _load_settings(user).get("unit_system")
    return value if value in UNIT_SYSTEMS else DEFAULT_UNIT_SYSTEM


async def set_unit_system(db: AsyncSession, user: User, unit_system: str) -> User:
    """Persist the user's unit_system preference, preserving any other settings keys."""
    data = _load_settings(user)
    data["unit_system"] = unit_system
    user.settings = json.dumps(data)
    await db.commit()
    await db.refresh(user)
    return user

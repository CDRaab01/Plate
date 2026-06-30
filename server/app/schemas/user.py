import uuid

from pydantic import BaseModel, field_validator

from app.nutrition.units import UNIT_SYSTEMS


class UserOut(BaseModel):
    id: uuid.UUID
    name: str
    email: str
    # The lb/kg + oz/g display preference. Derived from users.settings JSON (see user_service); the
    # router sets it explicitly so a missing/legacy value reads as the imperial default.
    unit_system: str = "imperial"

    model_config = {"from_attributes": True}


class UserSettingsUpdate(BaseModel):
    """Partial settings update. Only ``unit_system`` is settable today."""

    unit_system: str

    @field_validator("unit_system")
    @classmethod
    def unit_system_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in UNIT_SYSTEMS:
            raise ValueError(f"unit_system must be one of {UNIT_SYSTEMS}")
        return key

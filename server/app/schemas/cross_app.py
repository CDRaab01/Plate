"""Cross-app food resolution + diary logging for the sister app Cookbook (its Phase 7).

Cookbook stores free-text ingredients; these schemas carry them across the boundary so Plate —
the app that actually knows nutrition — resolves names against its ``foods`` table and does the
portion math. Everything here is best-effort estimation: unmatched or unscalable items come back
flagged, never guessed silently.
"""

import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.schemas.log import _validate_meal


class CrossAppFoodItem(BaseModel):
    """One free-text ingredient row as Cookbook stores it."""

    name: str
    quantity: float | None = Field(default=None, gt=0)
    unit: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()


class ResolveFoodsRequest(BaseModel):
    items: list[CrossAppFoodItem] = Field(default=[], max_length=200)


class ResolvedFoodOut(BaseModel):
    """The macro contribution of one ingredient, or why it couldn't be computed."""

    name: str
    matched: bool
    food_id: uuid.UUID | None = None
    food_name: str | None = None
    kcal: float | None = None
    protein_g: float | None = None
    carbs_g: float | None = None
    fat_g: float | None = None


class ResolveFoodsResponse(BaseModel):
    items: list[ResolvedFoodOut]


class CrossAppLogRequest(BaseModel):
    """Log a set of resolved-on-the-fly ingredients into the diary (one entry per match)."""

    date: datetime.date
    meal: str
    # Label used for skipped-item logging context only; entries carry their food names.
    recipe_name: str | None = None
    # Optional correlation tag the caller can later use to adjust/retract this exact log
    # (DELETE /cross-app/logged?client_ref=). Stored on each created entry as source_ref.
    client_ref: str | None = Field(default=None, max_length=128)
    items: list[CrossAppFoodItem] = Field(default=[], max_length=200)

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        return _validate_meal(v)


class CrossAppLogResponse(BaseModel):
    logged: int
    skipped: int


class CrossAppUnlogResponse(BaseModel):
    # How many diary entries were removed for the given (user, client_ref).
    removed: int

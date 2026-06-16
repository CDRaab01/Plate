import datetime
import uuid

from pydantic import BaseModel, field_validator

MEALS = ("breakfast", "lunch", "dinner", "snack")


def _validate_meal(v: str) -> str:
    meal = v.strip().lower()
    if meal not in MEALS:
        raise ValueError(f"meal must be one of {MEALS}")
    return meal


class LogEntryCreate(BaseModel):
    food_id: uuid.UUID
    date: datetime.date
    meal: str
    quantity: float
    unit: str

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        return _validate_meal(v)

    @field_validator("quantity")
    @classmethod
    def quantity_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("quantity must be positive")
        return v


class LogEntryUpdate(BaseModel):
    """Partial update — only the provided fields change. Quantity/unit changes re-snapshot macros."""

    date: datetime.date | None = None
    meal: str | None = None
    quantity: float | None = None
    unit: str | None = None

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str | None) -> str | None:
        return None if v is None else _validate_meal(v)

    @field_validator("quantity")
    @classmethod
    def quantity_positive(cls, v: float | None) -> float | None:
        if v is not None and v <= 0:
            raise ValueError("quantity must be positive")
        return v


class LogEntryOut(BaseModel):
    model_config = {"from_attributes": True}

    id: uuid.UUID
    food_id: uuid.UUID | None = None
    food_name: str | None = None
    date: datetime.date
    meal: str
    quantity: float
    unit: str

    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float | None = None
    sugar_g: float | None = None
    sat_fat_g: float | None = None
    cholesterol_mg: float | None = None
    sodium_mg: float | None = None


class TotalsOut(BaseModel):
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float
    sugar_g: float
    sat_fat_g: float
    cholesterol_mg: float
    sodium_mg: float


class MealGroup(BaseModel):
    meal: str
    entries: list[LogEntryOut]
    totals: TotalsOut


class DailyLog(BaseModel):
    """A day's log split into meals, with per-meal and day totals plus the (static) targets."""

    date: datetime.date
    meals: list[MealGroup]
    totals: TotalsOut
    targets: TotalsOut

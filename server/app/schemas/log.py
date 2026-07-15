import datetime
import uuid

from pydantic import BaseModel, field_validator

from app.schemas.food import FoodOut

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


class QuickAddCreate(BaseModel):
    """A MyFitnessPal-style quick add: raw macros logged directly, with no source food.

    Only the four primary macros are required; secondaries are optional. The entry is stored with
    ``food_id = None`` and the given (or default) ``name`` as its label.
    """

    date: datetime.date
    meal: str
    name: str | None = None
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float | None = None
    sugar_g: float | None = None
    sat_fat_g: float | None = None
    cholesterol_mg: float | None = None
    sodium_mg: float | None = None

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        return _validate_meal(v)

    @field_validator(
        "kcal",
        "protein_g",
        "carbs_g",
        "fat_g",
        "fiber_g",
        "sugar_g",
        "sat_fat_g",
        "cholesterol_mg",
        "sodium_mg",
    )
    @classmethod
    def non_negative(cls, v: float | None) -> float | None:
        if v is not None and v < 0:
            raise ValueError("macro values must not be negative")
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
    """A day's log split into meals, with per-meal and day totals plus the day's targets.

    ``trained_today`` reflects Spotter-awareness (§7): when the user trained that day the targets
    already include the training-day bump, and the client surfaces a "trained today" hint.
    """

    date: datetime.date
    meals: list[MealGroup]
    totals: TotalsOut
    targets: TotalsOut
    trained_today: bool = False
    # Consecutive days logged, ending today (or yesterday, one grace day). Relative to the
    # current date, so it reads as a "current streak" even when viewing a past day.
    streak: int = 0


class DaySummary(BaseModel):
    """One day's totals within a range summary, with that day's kcal target and training flag."""

    date: datetime.date
    totals: TotalsOut
    target_kcal: float
    trained: bool = False


class RangeSummaryOut(BaseModel):
    """A date-range (e.g. weekly) summary: per-day totals plus period totals and daily averages."""

    start: datetime.date
    end: datetime.date
    days: list[DaySummary]
    total: TotalsOut
    averages: TotalsOut


class RecentFoodOut(BaseModel):
    """A recently-logged food with the last portion used — the quick-log 'recent foods' surface,
    so re-logging a staple is one tap with the portion pre-filled."""

    food: FoodOut
    last_meal: str
    last_quantity: float
    last_unit: str


class CopyDayRequest(BaseModel):
    """Copy every entry from one day into another (the 'copy yesterday' quick-log)."""

    from_date: datetime.date
    to_date: datetime.date

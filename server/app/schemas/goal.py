"""Goals & computed-targets request/response models (CLAUDE.md §4, §7).

The goal carries the body inputs the targets engine needs. Enum-like fields are validated here so a
bad ``sex`` / ``activity_level`` / ``goal_type`` is a 422 at the edge rather than a math error deeper
in :mod:`app.nutrition.targets`.
"""
import datetime
import uuid

from pydantic import BaseModel, field_validator

GOAL_TYPES = ("maintain", "cut", "bulk")
SEXES = ("male", "female")
ACTIVITY_LEVELS = ("sedentary", "light", "moderate", "active", "very_active")


class GoalUpsert(BaseModel):
    """Set (or change) the active goal. Each call appends a new ``user_goals`` row; the most recent
    is the active goal, so history is preserved (see :class:`~app.models.user_goal.UserGoal`)."""

    goal_type: str
    weight_kg: float
    height_cm: float
    age: int
    sex: str
    activity_level: str
    # Target rate of weight change (kg/week): negative for a cut, positive for a bulk, 0 to maintain.
    rate_kg_per_week: float = 0.0

    @field_validator("goal_type")
    @classmethod
    def goal_type_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in GOAL_TYPES:
            raise ValueError(f"goal_type must be one of {GOAL_TYPES}")
        return key

    @field_validator("sex")
    @classmethod
    def sex_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in SEXES:
            raise ValueError(f"sex must be one of {SEXES}")
        return key

    @field_validator("activity_level")
    @classmethod
    def activity_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in ACTIVITY_LEVELS:
            raise ValueError(f"activity_level must be one of {ACTIVITY_LEVELS}")
        return key

    @field_validator("weight_kg", "height_cm")
    @classmethod
    def measurement_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("weight and height must be positive")
        return v

    @field_validator("age")
    @classmethod
    def age_in_range(cls, v: int) -> int:
        if not 1 <= v <= 120:
            raise ValueError("age must be between 1 and 120")
        return v


class GoalOut(BaseModel):
    model_config = {"from_attributes": True}

    id: uuid.UUID
    goal_type: str
    weight_kg: float
    height_cm: float
    age: int
    sex: str
    activity_level: str
    rate_kg_per_week: float
    created_at: datetime.datetime


class TargetsOut(BaseModel):
    """The computed kcal/macro targets for a date — what the diary shows the totals against."""

    date: datetime.date
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float

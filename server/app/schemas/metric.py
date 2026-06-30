import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import BODYFAT_BOUNDS

_BF_MIN, _BF_MAX = BODYFAT_BOUNDS


class BodyMetricCreate(BaseModel):
    """A weigh-in. ``weight`` is in ``unit`` (lb or kg); the server converts to canonical kg and
    bound-checks it (see :func:`app.services.metric_service.add_weight_metric`)."""

    date: datetime.date
    weight: float
    unit: str = "lb"
    bodyfat: float | None = Field(default=None, ge=_BF_MIN, le=_BF_MAX)

    @field_validator("weight")
    @classmethod
    def weight_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("weight must be positive")
        return v

    @field_validator("unit")
    @classmethod
    def unit_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in ("lb", "kg"):
            raise ValueError("unit must be 'lb' or 'kg'")
        return key


class BodyMetricOut(BaseModel):
    """A weigh-in formatted for the client.

    ``weight_kg`` is the canonical stored value (stable across the lb/kg toggle — use it for
    charting); ``weight`` + ``unit`` are the display value in the user's preferred unit.
    """

    id: uuid.UUID
    user_id: uuid.UUID
    date: datetime.date
    weight_kg: float
    weight: float
    unit: str
    bodyfat: float | None = None


class WeightTrendPoint(BaseModel):
    """One smoothed point on the weight-trend line, in the user's display unit."""

    date: datetime.date
    weight: float


class WeightTrendOut(BaseModel):
    """The weight trend formatted for display.

    Weights and rates are in the user's preferred unit (``unit`` / per ``unit``/week). ``status`` is
    one of ``on_pace | ahead | behind | insufficient_data``.
    """

    points: list[WeightTrendPoint]
    trend_weight: float | None
    observed_rate_per_week: float | None
    goal_rate_per_week: float
    unit: str
    status: str

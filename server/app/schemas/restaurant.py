"""Restaurant/chain meal logging schemas (checkbox templates + menu-parse draft).

A restaurant component carries its nutrition through exactly one of:

* ``food_id`` — an existing food (trusted-search USDA generic ⇒ estimate), or
* ``macros`` — inline **official** nutrition from the chain's published menu; the server mints a
  ``Food(source="user", brand=<restaurant name>)`` from it and links that.

Both may also be absent: an unlinked draft row is saved (the label is kept) but skipped when
logging until the user links a food.
"""

import datetime
import uuid

from pydantic import BaseModel, field_validator, model_validator

from app.schemas.log import _validate_meal

_UNITS = ("g", "oz", "serving")


def _validate_unit(v: str) -> str:
    unit = v.strip().lower()
    if unit not in _UNITS:
        raise ValueError(f"unit must be one of {_UNITS}")
    return unit


class ComponentMacrosIn(BaseModel):
    """Official per-serving nutrition stated by the chain (one component = one menu serving)."""

    serving_desc: str | None = None  # "1 scoop (4 oz)" — display only
    serving_grams: float | None = None  # when known, enables gram-basis logging
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fiber_g: float | None = None
    sugar_g: float | None = None
    sat_fat_g: float | None = None
    cholesterol_mg: float | None = None
    sodium_mg: float | None = None

    @field_validator("kcal", "protein_g", "carbs_g", "fat_g")
    @classmethod
    def macros_non_negative(cls, v: float) -> float:
        if v < 0:
            raise ValueError("Macro values must not be negative")
        return v

    @field_validator("serving_grams")
    @classmethod
    def grams_positive(cls, v: float | None) -> float | None:
        if v is not None and v <= 0:
            raise ValueError("serving_grams must be positive")
        return v


class RestaurantComponentIn(BaseModel):
    category: str
    name: str
    food_id: uuid.UUID | None = None
    macros: ComponentMacrosIn | None = None
    quantity: float = 1.0
    unit: str = "serving"
    default_checked: bool = False

    @field_validator("category")
    @classmethod
    def category_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("category must not be empty")
        return v.strip()[:64]

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()

    @field_validator("quantity")
    @classmethod
    def quantity_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("quantity must be positive")
        return v

    @field_validator("unit")
    @classmethod
    def unit_valid(cls, v: str) -> str:
        return _validate_unit(v)

    @model_validator(mode="after")
    def food_xor_macros(self) -> "RestaurantComponentIn":
        if self.food_id is not None and self.macros is not None:
            raise ValueError("provide food_id or macros, not both")
        return self


class RestaurantCreate(BaseModel):
    name: str
    menu_url: str | None = None
    notes: str | None = None
    shared: bool = True
    components: list[RestaurantComponentIn] = []

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()


class RestaurantUpdate(BaseModel):
    """Partial update of name/menu_url/notes/shared (components are replaced via PUT)."""

    name: str | None = None
    menu_url: str | None = None
    notes: str | None = None
    shared: bool | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()


class RestaurantComponentsReplace(BaseModel):
    components: list[RestaurantComponentIn] = []


class RestaurantComponentOut(BaseModel):
    id: uuid.UUID
    category: str
    name: str
    food_id: uuid.UUID | None = None
    food_name: str | None = None  # the estimate source ("Rice, white, cooked"), shown as "≈ …"
    quantity: float
    unit: str
    order: int
    default_checked: bool
    # Macros at the component's default portion, from the current linked food (None if unlinked).
    kcal: float | None = None
    protein_g: float | None = None
    carbs_g: float | None = None
    fat_g: float | None = None


class RestaurantOut(BaseModel):
    id: uuid.UUID
    name: str
    menu_url: str | None = None
    notes: str | None = None
    shared: bool
    is_owner: bool  # client hides edit/delete on other accounts' shared restaurants
    components: list[RestaurantComponentOut]  # flat, ordered; the client groups by category


class RestaurantLogSelection(BaseModel):
    component_id: uuid.UUID
    quantity: float | None = None  # override; None = the component's default

    @field_validator("quantity")
    @classmethod
    def quantity_positive(cls, v: float | None) -> float | None:
        if v is not None and v <= 0:
            raise ValueError("quantity must be positive")
        return v


class RestaurantLogRequest(BaseModel):
    """Log the ticked components into a day's meal — one food_log_entry per selection."""

    date: datetime.date
    meal: str
    selections: list[RestaurantLogSelection]

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        return _validate_meal(v)

    @field_validator("selections")
    @classmethod
    def selections_nonempty(cls, v: list[RestaurantLogSelection]) -> list[RestaurantLogSelection]:
        if not v:
            raise ValueError("selections must not be empty")
        return v

import uuid

from pydantic import BaseModel, field_validator


class PortionOut(BaseModel):
    """A named household measure ("1 cup, sliced" = 240 g) the picker can offer."""

    model_config = {"from_attributes": True}

    id: uuid.UUID
    description: str
    gram_weight: float


class FoodOut(BaseModel):
    """A canonical food returned by search / lookup. Carries both nutrition bases so the client
    can let the user log by grams or by serving without a round-trip."""

    model_config = {"from_attributes": True}

    id: uuid.UUID
    source: str
    name: str
    brand: str | None = None
    barcode: str | None = None
    serving_size: float | None = None
    serving_unit: str | None = None
    serving_label: str | None = None
    macros_incomplete: bool = False

    kcal_per_100g: float
    protein_g_per_100g: float
    carbs_g_per_100g: float
    fat_g_per_100g: float
    fiber_g_per_100g: float | None = None
    sugar_g_per_100g: float | None = None
    sat_fat_g_per_100g: float | None = None
    cholesterol_mg_per_100g: float | None = None
    sodium_mg_per_100g: float | None = None

    kcal_per_serving: float | None = None
    protein_g_per_serving: float | None = None
    carbs_g_per_serving: float | None = None
    fat_g_per_serving: float | None = None
    fiber_g_per_serving: float | None = None
    sugar_g_per_serving: float | None = None
    sat_fat_g_per_serving: float | None = None
    cholesterol_mg_per_serving: float | None = None
    sodium_mg_per_serving: float | None = None


class FoodDetailOut(FoodOut):
    """A single food with its named portions — the add-dialog payload.

    Search stays :class:`FoodOut` (no portions) to keep result pages lean; the client fetches
    this on food-tap. Serialize only foods loaded via ``get_food_detail`` — the ``portions``
    relationship is ``lazy="raise"`` and needs its explicit eager load.
    """

    portions: list[PortionOut] = []


class FoodCreate(BaseModel):
    """A user-defined custom food (``source='user'``) for items not in USDA/OFF."""

    name: str
    brand: str | None = None
    serving_size: float | None = None
    serving_unit: str | None = None

    kcal_per_100g: float
    protein_g_per_100g: float
    carbs_g_per_100g: float
    fat_g_per_100g: float
    fiber_g_per_100g: float | None = None
    sugar_g_per_100g: float | None = None
    sat_fat_g_per_100g: float | None = None
    cholesterol_mg_per_100g: float | None = None
    sodium_mg_per_100g: float | None = None

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("Name must not be blank")
        return v.strip()

    @field_validator("kcal_per_100g", "protein_g_per_100g", "carbs_g_per_100g", "fat_g_per_100g")
    @classmethod
    def macros_non_negative(cls, v: float) -> float:
        if v < 0:
            raise ValueError("Macro values must not be negative")
        return v

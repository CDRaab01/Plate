import uuid

from pydantic import BaseModel, field_validator


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

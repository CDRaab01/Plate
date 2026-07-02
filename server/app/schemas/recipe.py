import datetime
import uuid

from pydantic import BaseModel, field_validator

from app.schemas.log import TotalsOut, _validate_meal


class RecipeItemIn(BaseModel):
    food_id: uuid.UUID
    quantity: float
    unit: str

    @field_validator("quantity")
    @classmethod
    def quantity_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("quantity must be positive")
        return v


class RecipeCreate(BaseModel):
    name: str
    description: str | None = None
    items: list[RecipeItemIn] = []

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()


class RecipeUpdate(BaseModel):
    """Partial update of a recipe's name/description (items are replaced via PUT /items)."""

    name: str | None = None
    description: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()


class RecipeItemsReplace(BaseModel):
    items: list[RecipeItemIn] = []


class RecipeItemOut(BaseModel):
    id: uuid.UUID
    food_id: uuid.UUID | None = None
    food_name: str | None = None
    quantity: float
    unit: str
    order: int
    # Per-item macros, scaled from the current source food (None if the food was deleted).
    kcal: float | None = None
    protein_g: float | None = None
    carbs_g: float | None = None
    fat_g: float | None = None


class RecipeOut(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None = None
    items: list[RecipeItemOut]
    totals: TotalsOut


class RecipeLogRequest(BaseModel):
    """Log a whole recipe into a day's meal — expands into one food_log_entry per item."""

    date: datetime.date
    meal: str

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        return _validate_meal(v)


class RecipeExportItem(BaseModel):
    """One ingredient row in the cross-app export: the food's display name + amount."""

    food_name: str
    quantity: float
    unit: str


class RecipeExport(BaseModel):
    """Cross-app recipe export for Cookbook's one-time migration (its Phase 6).

    Deliberately food-id-free: Cookbook stores free-text ingredients, so only names and
    amounts cross the boundary. ``id`` becomes Cookbook's ``source_id`` for dedupe.
    """

    id: uuid.UUID
    name: str
    description: str | None = None
    items: list[RecipeExportItem]


class DiscoveredRecipe(BaseModel):
    """A recipe-discovery search hit (from the external provider)."""

    source_id: str
    title: str
    image: str | None = None
    ready_in_minutes: int | None = None
    servings: int | None = None


class RecipeImportRequest(BaseModel):
    """Import an external recipe (by its provider id) as a saved Plate recipe."""

    source_id: str

    @field_validator("source_id")
    @classmethod
    def source_id_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("source_id must not be empty")
        return v.strip()

"""External recipe sources — discover real recipes and normalize them for import.

Mirrors the :mod:`app.foods` ``FoodSource`` pattern: a provider-agnostic interface plus normalized
dataclasses, so the concrete provider (Spoonacular) is swappable and the service/tests depend only
on the normalized shapes. An imported recipe is turned into a Plate ``Recipe`` whose ingredients are
``Food`` rows, so the existing recipe log flow (``/recipes/{id}/log``) can add all its parts to a
meal.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass(frozen=True)
class RecipeSummary:
    """A discovery hit — enough to show a result row and import on tap."""

    source_id: str
    title: str
    image: str | None = None
    ready_in_minutes: int | None = None
    servings: int | None = None


@dataclass(frozen=True)
class NormalizedIngredient:
    """One recipe ingredient with the nutrition for the amount used in the recipe.

    ``grams`` is the ingredient's weight when the provider gives one; the importer then stores a
    per-100g basis and logs by grams. When ``grams`` is absent the importer stores the macros as a
    single serving instead. Either way the logged macros equal this ingredient's contribution.
    """

    name: str
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    grams: float | None = None
    # Display string for the amount as written in the recipe (e.g. "2 breasts"), for the UI.
    amount_text: str | None = None
    fiber_g: float | None = None
    sugar_g: float | None = None
    sat_fat_g: float | None = None
    cholesterol_mg: float | None = None
    sodium_mg: float | None = None


@dataclass(frozen=True)
class NormalizedRecipe:
    """A full recipe ready to import: metadata + ingredients (each with macros)."""

    source_id: str
    title: str
    ingredients: list[NormalizedIngredient]
    image: str | None = None
    servings: int | None = None
    ready_in_minutes: int | None = None
    source_url: str | None = None
    instructions: str | None = None
    summary: str | None = None
    extra: dict = field(default_factory=dict)


class RecipeSource(ABC):
    """A provider of external recipes (search + fetch-one)."""

    source_tag: str = "external"

    @abstractmethod
    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        """Search recipes by free-text query."""

    @abstractmethod
    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        """Fetch one recipe (with per-ingredient nutrition) by its provider id."""

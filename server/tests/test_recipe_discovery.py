"""External recipe discovery + import tests (CLAUDE.md §10).

Pure Spoonacular normalization plus service-level discover/import with a mocked RecipeSource (no
live calls). Import turns each ingredient into a loggable food, so the imported recipe is a normal
Plate recipe that can be logged to a meal.
"""
import datetime
import uuid

import pytest

from app.database import AsyncSessionLocal
from app.models.user import User
from app.recipes_ext.base import (
    NormalizedIngredient,
    NormalizedRecipe,
    RecipeSource,
    RecipeSummary,
)
from app.recipes_ext.spoonacular import normalize_information
from app.services.recipe_discovery_service import discover_recipes, import_recipe
from app.services.recipe_service import log_recipe


class FakeRecipeSource(RecipeSource):
    source_tag = "fake"

    def __init__(self, summaries=None, recipe=None):
        self._summaries = summaries or []
        self._recipe = recipe

    async def discover(self, query: str, *, limit: int):
        return self._summaries

    async def fetch(self, source_id: str):
        return self._recipe


async def _make_user() -> uuid.UUID:
    async with AsyncSessionLocal() as db:
        u = User(name="R", email=f"r_{uuid.uuid4().hex[:8]}@plate.com", hashed_password="x")
        db.add(u)
        await db.commit()
        await db.refresh(u)
        return u.id


def _recipe() -> NormalizedRecipe:
    return NormalizedRecipe(
        source_id="123",
        title=f"Test Bowl {uuid.uuid4().hex[:6]}",
        servings=2,
        image="http://img",
        source_url="http://x",
        ingredients=[
            NormalizedIngredient("chicken breast", kcal=330, protein_g=62, carbs_g=0, fat_g=7, grams=200),
            NormalizedIngredient("olive oil", kcal=119, protein_g=0, carbs_g=0, fat_g=14),  # no grams
        ],
    )


# ── Pure Spoonacular normalization ────────────────────────────────────────────


def test_normalize_information_maps_ingredients_and_grams():
    payload = {
        "id": 123,
        "title": "Bowl",
        "servings": 2,
        "readyInMinutes": 20,
        "sourceUrl": "http://x",
        "nutrition": {
            "ingredients": [
                {
                    "name": "chicken breast",
                    "amount": 200,
                    "unit": "g",
                    "nutrients": [
                        {"name": "Calories", "amount": 330},
                        {"name": "Protein", "amount": 62},
                        {"name": "Carbohydrates", "amount": 0},
                        {"name": "Fat", "amount": 7},
                        {"name": "Sodium", "amount": 140},
                    ],
                },
                {
                    "name": "olive oil",
                    "amount": 1,
                    "unit": "tbsp",
                    "nutrients": [
                        {"name": "Calories", "amount": 119},
                        {"name": "Protein", "amount": 0},
                        {"name": "Carbohydrates", "amount": 0},
                        {"name": "Fat", "amount": 14},
                    ],
                },
                {"name": "mystery", "amount": 1, "unit": "g", "nutrients": []},  # dropped (no macros)
            ]
        },
    }
    r = normalize_information(payload)
    assert r.title == "Bowl" and r.servings == 2 and r.source_id == "123"
    assert len(r.ingredients) == 2  # mystery dropped
    chicken, oil = r.ingredients
    assert chicken.grams == 200 and chicken.kcal == 330 and chicken.sodium_mg == 140
    assert oil.grams is None and oil.kcal == 119  # tbsp → no gram basis


# ── Discovery ─────────────────────────────────────────────────────────────────


async def test_discover_maps_summaries():
    src = FakeRecipeSource(summaries=[RecipeSummary("9", "Chicken Rice", image="i", servings=3)])
    hits = await discover_recipes("chicken", source=src)
    assert len(hits) == 1 and hits[0].source_id == "9" and hits[0].title == "Chicken Rice"


async def test_discover_disabled_without_key():
    # No injected source and no SPOONACULAR_API_KEY in the test env → 503.
    with pytest.raises(Exception) as exc:
        await discover_recipes("chicken")
    assert getattr(exc.value, "status_code", None) == 503


async def test_discover_blank_query_returns_empty():
    src = FakeRecipeSource(summaries=[RecipeSummary("9", "x")])
    assert await discover_recipes("   ", source=src) == []


# ── Import ────────────────────────────────────────────────────────────────────


async def test_import_creates_recipe_with_ingredient_macros():
    user_id = await _make_user()
    src = FakeRecipeSource(recipe=_recipe())
    async with AsyncSessionLocal() as db:
        out = await import_recipe(db, user_id, "123", source=src)

    assert len(out.items) == 2
    by_name = {i.food_name: i for i in out.items}
    chicken = by_name["chicken breast"]
    assert chicken.unit == "g" and chicken.quantity == 200
    assert chicken.kcal == pytest.approx(330)  # grams path reproduces the ingredient macros
    oil = by_name["olive oil"]
    assert oil.unit == "serving" and oil.quantity == 1
    assert oil.kcal == pytest.approx(119)  # serving path
    assert out.totals.kcal == pytest.approx(449)


async def test_import_then_log_adds_all_parts_to_a_meal():
    user_id = await _make_user()
    src = FakeRecipeSource(recipe=_recipe())
    async with AsyncSessionLocal() as db:
        out = await import_recipe(db, user_id, "123", source=src)
    async with AsyncSessionLocal() as db:
        entries = await log_recipe(db, user_id, out.id, datetime.date(2026, 6, 30), "dinner")
    assert len(entries) == 2
    assert sum(e.kcal for e in entries) == pytest.approx(449)
    assert all(e.meal == "dinner" for e in entries)


async def test_import_not_found_when_source_returns_none():
    user_id = await _make_user()
    src = FakeRecipeSource(recipe=None)
    with pytest.raises(Exception) as exc:
        async with AsyncSessionLocal() as db:
            await import_recipe(db, user_id, "999", source=src)
    assert getattr(exc.value, "status_code", None) == 404

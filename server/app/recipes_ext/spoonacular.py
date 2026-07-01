"""Spoonacular recipe source (server-side key, never shipped in the APK).

Spoonacular returns structured ingredients with per-ingredient nutrition, which is what a macro
tracker needs. We map ``complexSearch`` → discovery rows and ``/recipes/{id}/information?
includeNutrition=true`` → a :class:`NormalizedRecipe`, pulling each ingredient's Calories/Protein/
Carbs/Fat (+ secondaries) from its ``nutrition.ingredients[].nutrients``.
"""

import logging

import httpx

from app.recipes_ext.base import (
    NormalizedIngredient,
    NormalizedRecipe,
    RecipeSource,
    RecipeSummary,
)

log = logging.getLogger(__name__)

_GRAM_UNITS = {"g", "gram", "grams"}


def _nutrient(nutrients: list[dict], name: str) -> float | None:
    for n in nutrients:
        if n.get("name") == name:
            return n.get("amount")
    return None


def _amount_text(amount, unit) -> str | None:
    if amount is None:
        return None
    qty = f"{amount:g}" if isinstance(amount, (int, float)) else str(amount)
    unit = (unit or "").strip()
    return f"{qty} {unit}".strip()


def normalize_information(data: dict) -> NormalizedRecipe:
    """Map a Spoonacular recipe-information payload (with nutrition) to a NormalizedRecipe."""
    nutrition = data.get("nutrition") or {}
    ingredients: list[NormalizedIngredient] = []
    for ing in nutrition.get("ingredients", []) or []:
        nutrients = ing.get("nutrients") or []
        kcal = _nutrient(nutrients, "Calories")
        protein = _nutrient(nutrients, "Protein")
        carbs = _nutrient(nutrients, "Carbohydrates")
        fat = _nutrient(nutrients, "Fat")
        if None in (kcal, protein, carbs, fat):
            continue  # skip ingredients we can't macro-account for
        amount = ing.get("amount")
        unit = (ing.get("unit") or "").strip().lower()
        grams = amount if (unit in _GRAM_UNITS and isinstance(amount, (int, float))) else None
        ingredients.append(
            NormalizedIngredient(
                name=(ing.get("name") or "ingredient").strip(),
                kcal=kcal,
                protein_g=protein,
                carbs_g=carbs,
                fat_g=fat,
                grams=grams,
                amount_text=_amount_text(amount, ing.get("unit")),
                fiber_g=_nutrient(nutrients, "Fiber"),
                sugar_g=_nutrient(nutrients, "Sugar"),
                sat_fat_g=_nutrient(nutrients, "Saturated Fat"),
                cholesterol_mg=_nutrient(nutrients, "Cholesterol"),
                sodium_mg=_nutrient(nutrients, "Sodium"),
            )
        )
    return NormalizedRecipe(
        source_id=str(data.get("id")),
        title=(data.get("title") or "Recipe").strip(),
        ingredients=ingredients,
        image=data.get("image"),
        servings=data.get("servings"),
        ready_in_minutes=data.get("readyInMinutes"),
        source_url=data.get("sourceUrl"),
        instructions=data.get("instructions"),
        summary=data.get("summary"),
    )


class SpoonacularSource(RecipeSource):
    source_tag = "spoonacular"

    def __init__(self, client: httpx.AsyncClient, api_key: str, base_url: str):
        self._client = client
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")

    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        resp = await self._client.get(
            f"{self._base_url}/recipes/complexSearch",
            params={
                "apiKey": self._api_key,
                "query": query,
                "number": limit,
                "addRecipeInformation": "true",
            },
        )
        resp.raise_for_status()
        out: list[RecipeSummary] = []
        for r in resp.json().get("results", []) or []:
            rid = r.get("id")
            if rid is None:
                continue
            out.append(
                RecipeSummary(
                    source_id=str(rid),
                    title=(r.get("title") or "").strip(),
                    image=r.get("image"),
                    ready_in_minutes=r.get("readyInMinutes"),
                    servings=r.get("servings"),
                )
            )
        return out

    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        resp = await self._client.get(
            f"{self._base_url}/recipes/{source_id}/information",
            params={"apiKey": self._api_key, "includeNutrition": "true"},
        )
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return normalize_information(resp.json())

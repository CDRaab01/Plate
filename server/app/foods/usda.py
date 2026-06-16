"""USDA FoodData Central source — generic/whole foods (CLAUDE.md §5).

The FDC API key is **server-side only** (never shipped in the APK). Foundation and SR Legacy
datasets report nutrients per 100g, which is exactly our primary storage basis, so no rescaling is
needed. Branded items additionally carry a serving, which we capture when present.
"""
import logging

import httpx

from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood

log = logging.getLogger(__name__)

# FDC identifies nutrients by a stable number regardless of label wording — map those we store.
_NUTRIENT_KCAL = "208"
_NUTRIENT_PROTEIN = "203"
_NUTRIENT_FAT = "204"
_NUTRIENT_CARBS = "205"
_NUTRIENT_FIBER = "291"
_NUTRIENT_SUGAR = "269"
_NUTRIENT_SAT_FAT = "606"
_NUTRIENT_CHOLESTEROL = "601"  # already mg in FDC
_NUTRIENT_SODIUM = "307"  # already mg in FDC


def _nutrient_map(food: dict) -> dict[str, float]:
    """Index a food's ``foodNutrients`` by nutrient number → amount (per 100g)."""
    out: dict[str, float] = {}
    for n in food.get("foodNutrients", []) or []:
        number = n.get("nutrientNumber") or n.get("number")
        value = n.get("value")
        if number is None or value is None:
            continue
        out[str(number)] = value
    return out


def normalize_usda_food(food: dict) -> NormalizedFood | None:
    """Map one FDC ``foods`` entry to a :class:`NormalizedFood`, or ``None`` if unusable.

    A record is only useful to us if it carries the four primary macros; anything missing those is
    skipped rather than persisted with silent zeros.
    """
    name = (food.get("description") or "").strip()
    if not name:
        return None
    nutrients = _nutrient_map(food)
    required = (_NUTRIENT_KCAL, _NUTRIENT_PROTEIN, _NUTRIENT_CARBS, _NUTRIENT_FAT)
    if any(key not in nutrients for key in required):
        return None

    fdc_id = food.get("fdcId")
    return NormalizedFood(
        source="usda",
        source_id=str(fdc_id) if fdc_id is not None else None,
        name=name,
        brand=(food.get("brandOwner") or food.get("brandName") or None),
        barcode=(food.get("gtinUpc") or None),
        serving_size=food.get("servingSize"),
        serving_unit=food.get("servingSizeUnit"),
        kcal_per_100g=nutrients[_NUTRIENT_KCAL],
        protein_g_per_100g=nutrients[_NUTRIENT_PROTEIN],
        carbs_g_per_100g=nutrients[_NUTRIENT_CARBS],
        fat_g_per_100g=nutrients[_NUTRIENT_FAT],
        fiber_g_per_100g=nutrients.get(_NUTRIENT_FIBER),
        sugar_g_per_100g=nutrients.get(_NUTRIENT_SUGAR),
        sat_fat_g_per_100g=nutrients.get(_NUTRIENT_SAT_FAT),
        cholesterol_mg_per_100g=nutrients.get(_NUTRIENT_CHOLESTEROL),
        sodium_mg_per_100g=nutrients.get(_NUTRIENT_SODIUM),
    )


class UsdaFoodSource(FoodSource):
    source_tag = "usda"

    def __init__(self, client: httpx.AsyncClient, api_key: str, base_url: str):
        self._client = client
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")

    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        resp = await self._client.get(
            f"{self._base_url}/foods/search",
            params={
                "api_key": self._api_key,
                "query": query,
                "pageSize": limit,
                "dataType": "Foundation,SR Legacy,Branded",
            },
        )
        resp.raise_for_status()
        payload = resp.json()
        out: list[NormalizedFood] = []
        for raw in payload.get("foods", []) or []:
            normalized = normalize_usda_food(raw)
            if normalized is not None:
                out.append(normalized)
        return out

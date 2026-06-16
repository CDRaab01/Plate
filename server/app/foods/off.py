"""Open Food Facts source — packaged goods & barcodes (CLAUDE.md §5).

OFF is free and keyless but requires a descriptive ``User-Agent`` and is rate-sensitive, so we
request only the nutriments we store via ``fields=`` and rely on the caching layer to avoid
repeat hits. Data is ODbL-licensed → the app must show "Data from Open Food Facts, ODbL"
(handled by the Phase 4 attribution screen).

OFF reports energy in kcal per 100g and macros in grams per 100g; sodium and cholesterol are in
**grams**, which we convert to milligrams to match our storage basis.
"""
import logging

import httpx

from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood

log = logging.getLogger(__name__)

# Only the nutriments we persist — keeps OFF responses small and within rate-limit etiquette.
_FIELDS = "code,product_name,brands,nutriments,serving_size,serving_quantity"

_G_TO_MG = 1000.0


def _num(value) -> float | None:
    """Coerce an OFF nutriment value (str | number | None) to float, or None if absent/blank."""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _mg(value) -> float | None:
    grams = _num(value)
    return None if grams is None else grams * _G_TO_MG


def normalize_off_product(product: dict) -> NormalizedFood | None:
    """Map one OFF product to a :class:`NormalizedFood`, or ``None`` if the macros are missing."""
    name = (product.get("product_name") or "").strip()
    if not name:
        return None
    nutriments = product.get("nutriments") or {}

    kcal = _num(nutriments.get("energy-kcal_100g"))
    protein = _num(nutriments.get("proteins_100g"))
    carbs = _num(nutriments.get("carbohydrates_100g"))
    fat = _num(nutriments.get("fat_100g"))
    if None in (kcal, protein, carbs, fat):
        return None

    brands = product.get("brands")
    brand = brands.split(",")[0].strip() if brands else None
    serving_qty = _num(product.get("serving_quantity"))

    return NormalizedFood(
        source="off",
        source_id=(product.get("code") or None),
        name=name,
        brand=brand,
        barcode=(product.get("code") or None),
        serving_size=serving_qty,
        serving_unit="g" if serving_qty is not None else None,
        kcal_per_100g=kcal,
        protein_g_per_100g=protein,
        carbs_g_per_100g=carbs,
        fat_g_per_100g=fat,
        fiber_g_per_100g=_num(nutriments.get("fiber_100g")),
        sugar_g_per_100g=_num(nutriments.get("sugars_100g")),
        sat_fat_g_per_100g=_num(nutriments.get("saturated-fat_100g")),
        cholesterol_mg_per_100g=_mg(nutriments.get("cholesterol_100g")),
        sodium_mg_per_100g=_mg(nutriments.get("sodium_100g")),
        kcal_per_serving=_num(nutriments.get("energy-kcal_serving")),
        protein_g_per_serving=_num(nutriments.get("proteins_serving")),
        carbs_g_per_serving=_num(nutriments.get("carbohydrates_serving")),
        fat_g_per_serving=_num(nutriments.get("fat_serving")),
        fiber_g_per_serving=_num(nutriments.get("fiber_serving")),
        sugar_g_per_serving=_num(nutriments.get("sugars_serving")),
        sat_fat_g_per_serving=_num(nutriments.get("saturated-fat_serving")),
        cholesterol_mg_per_serving=_mg(nutriments.get("cholesterol_serving")),
        sodium_mg_per_serving=_mg(nutriments.get("sodium_serving")),
    )


class OpenFoodFactsSource(FoodSource):
    source_tag = "off"

    def __init__(self, client: httpx.AsyncClient, base_url: str, user_agent: str):
        self._client = client
        self._base_url = base_url.rstrip("/")
        self._user_agent = user_agent

    @property
    def _headers(self) -> dict[str, str]:
        return {"User-Agent": self._user_agent}

    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        resp = await self._client.get(
            f"{self._base_url}/cgi/search.pl",
            params={
                "search_terms": query,
                "search_simple": 1,
                "action": "process",
                "json": 1,
                "page_size": limit,
                "fields": _FIELDS,
            },
            headers=self._headers,
        )
        resp.raise_for_status()
        payload = resp.json()
        out: list[NormalizedFood] = []
        for raw in payload.get("products", []) or []:
            normalized = normalize_off_product(raw)
            if normalized is not None:
                out.append(normalized)
        return out

    async def fetch_barcode(self, barcode: str) -> NormalizedFood | None:
        """Look up a single product by barcode (used by the Phase 4 scan path)."""
        resp = await self._client.get(
            f"{self._base_url}/api/v2/product/{barcode}.json",
            params={"fields": _FIELDS},
            headers=self._headers,
        )
        resp.raise_for_status()
        payload = resp.json()
        if payload.get("status") != 1:
            return None
        return normalize_off_product(payload.get("product") or {})

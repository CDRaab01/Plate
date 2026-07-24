"""Open Food Facts source — packaged goods & barcodes (CLAUDE.md §5).

OFF is free and keyless but requires a descriptive ``User-Agent`` and is rate-sensitive, so we
request only the nutriments we store via ``fields=`` and rely on the caching layer to avoid
repeat hits. Data is ODbL-licensed → the app must show "Data from Open Food Facts, ODbL"
(handled by the Phase 4 attribution screen).

OFF reports energy in kcal per 100g and macros in grams per 100g; sodium and cholesterol are in
**grams**, which we convert to milligrams to match our storage basis.
"""

import logging
import re

import httpx

from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood, NormalizedPortion, resolve_primary_macros

log = logging.getLogger(__name__)

# The household text inside an OFF serving label: "30 g (2 cookies)" → "2 cookies".
_SERVING_PARENTHETICAL = re.compile(r"\(([^)]+)\)")

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


def _off_portion(serving_label: str | None, serving_qty: float | None) -> list[NormalizedPortion]:
    """One named portion from OFF's serving data, when it's usable.

    ``serving_size`` is a free-text label like ``"30 g (2 cookies)"`` and ``serving_quantity``
    the numeric size. The parenthetical is the household measure; without one, the whole label
    is the best name we have. Note OFF's ``serving_quantity`` is assumed to be grams — a
    pre-existing approximation (ml ≈ g for liquids) shared with ``serving_size`` storage.
    """
    if serving_label is None or serving_qty is None or serving_qty <= 0:
        return []
    match = _SERVING_PARENTHETICAL.search(serving_label)
    description = (match.group(1) if match else serving_label).strip()
    if not description:
        return []
    return [NormalizedPortion(description=description[:64], gram_weight=serving_qty, source="off")]


def normalize_off_product(product: dict) -> NormalizedFood | None:
    """Map one OFF product to a :class:`NormalizedFood`, or ``None`` if unusable.

    Sparse records are kept when energy is stated or Atwater-derivable (missing single macros
    imputed as zero + flagged); only records whose energy can't be established are dropped.
    """
    name = (product.get("product_name") or "").strip()
    if not name:
        return None
    nutriments = product.get("nutriments") or {}

    resolved = resolve_primary_macros(
        _num(nutriments.get("energy-kcal_100g")),
        _num(nutriments.get("proteins_100g")),
        _num(nutriments.get("carbohydrates_100g")),
        _num(nutriments.get("fat_100g")),
    )
    if resolved is None:
        return None
    kcal, protein, carbs, fat, incomplete = resolved

    brands = product.get("brands")
    brand = brands.split(",")[0].strip() if brands else None
    serving_qty = _num(product.get("serving_quantity"))
    serving_label = (product.get("serving_size") or "").strip() or None

    return NormalizedFood(
        source="off",
        source_id=(product.get("code") or None),
        name=name,
        brand=brand,
        barcode=(product.get("code") or None),
        serving_size=serving_qty,
        serving_unit="g" if serving_qty is not None else None,
        serving_label=serving_label[:64] if serving_label else None,
        macros_incomplete=incomplete,
        portions=_off_portion(serving_label, serving_qty),
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

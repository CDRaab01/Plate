"""USDA FoodData Central source — generic/whole foods (CLAUDE.md §5).

The FDC API key is **server-side only** (never shipped in the APK). Foundation and SR Legacy
datasets report nutrients per 100g, which is exactly our primary storage basis, so no rescaling is
needed. Branded items additionally carry a serving, which we capture when present.
"""

import logging

import httpx

from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood, NormalizedPortion, resolve_primary_macros

log = logging.getLogger(__name__)

#: All datasets the live search consults by default; the search filter can narrow this
#: (generic → "Foundation,SR Legacy", branded → "Branded").
DEFAULT_DATA_TYPES = "Foundation,SR Legacy,Branded"

# foodPortions rows whose measure/description carry no human-usable label.
_UNUSABLE_PORTION_TEXT = {"undetermined", "quantity not specified"}

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


def _portion_label(raw: dict) -> str | None:
    """Human label for one ``foodPortions`` entry, or ``None`` when it has no usable text.

    FDC spreads the text across fields by dataset: SR Legacy/Survey use ``portionDescription``
    ("1 cup, diced"); Foundation typically has ``measureUnit.name == "undetermined"`` with the
    real measure in ``modifier`` ("1 cup"); some rows carry a proper ``measureUnit`` plus a
    ``modifier`` qualifier ("sliced").
    """
    description = (raw.get("portionDescription") or "").strip()
    if description and description.lower() not in _UNUSABLE_PORTION_TEXT:
        return description

    unit = ((raw.get("measureUnit") or {}).get("name") or "").strip()
    if unit.lower() in _UNUSABLE_PORTION_TEXT:
        unit = ""
    modifier = (raw.get("modifier") or "").strip()
    if modifier.lower() in _UNUSABLE_PORTION_TEXT:
        modifier = ""

    measure = unit or modifier
    if not measure:
        return None
    amount = raw.get("amount")
    prefix = f"{amount:g} " if isinstance(amount, (int, float)) and amount > 0 else ""
    # When both are present the modifier qualifies the unit: "1 cup, sliced".
    suffix = f", {modifier}" if unit and modifier else ""
    return f"{prefix}{measure}{suffix}"


def parse_fdc_portions(food: dict, *, max_portions: int = 8) -> list[NormalizedPortion]:
    """Map an FDC record's ``foodPortions`` (detail/bulk shape) to normalized portions.

    Search responses never carry ``foodPortions`` — only ``GET /food/{fdcId}`` and the bulk
    JSON exports do. Rows without a positive ``gramWeight`` or a usable label are skipped;
    output is ordered by ``sequenceNumber``, de-duplicated by label, and capped.
    """
    parsed: list[tuple[int, NormalizedPortion]] = []
    seen: set[str] = set()
    for raw in food.get("foodPortions", []) or []:
        gram_weight = raw.get("gramWeight")
        if not isinstance(gram_weight, (int, float)) or gram_weight <= 0:
            continue
        label = _portion_label(raw)
        if label is None:
            continue
        label = label[:64]
        if label.lower() in seen:
            continue
        seen.add(label.lower())
        sequence = raw.get("sequenceNumber")
        parsed.append(
            (
                sequence if isinstance(sequence, int) else 10_000,
                NormalizedPortion(description=label, gram_weight=float(gram_weight), source="usda"),
            )
        )
    parsed.sort(key=lambda pair: pair[0])
    return [
        portion.model_copy(update={"sort_order": i})
        for i, (_, portion) in enumerate(parsed[:max_portions])
    ]


def normalize_usda_food(food: dict) -> NormalizedFood | None:
    """Map one FDC ``foods`` entry to a :class:`NormalizedFood`, or ``None`` if unusable.

    Sparse records are kept when their energy is stated or Atwater-derivable (missing single
    macros are imputed as zero and flagged ``macros_incomplete``); only records whose energy
    can't be established are dropped.
    """
    name = (food.get("description") or "").strip()
    if not name:
        return None
    nutrients = _nutrient_map(food)
    resolved = resolve_primary_macros(
        nutrients.get(_NUTRIENT_KCAL),
        nutrients.get(_NUTRIENT_PROTEIN),
        nutrients.get(_NUTRIENT_CARBS),
        nutrients.get(_NUTRIENT_FAT),
    )
    if resolved is None:
        return None
    kcal, protein, carbs, fat, incomplete = resolved

    fdc_id = food.get("fdcId")
    serving_label = (food.get("householdServingFullText") or "").strip() or None
    return NormalizedFood(
        source="usda",
        source_id=str(fdc_id) if fdc_id is not None else None,
        name=name,
        brand=(food.get("brandOwner") or food.get("brandName") or None),
        barcode=(food.get("gtinUpc") or None),
        serving_size=food.get("servingSize"),
        serving_unit=food.get("servingSizeUnit"),
        serving_label=serving_label[:64] if serving_label else None,
        macros_incomplete=incomplete,
        portions=parse_fdc_portions(food),
        kcal_per_100g=kcal,
        protein_g_per_100g=protein,
        carbs_g_per_100g=carbs,
        fat_g_per_100g=fat,
        fiber_g_per_100g=nutrients.get(_NUTRIENT_FIBER),
        sugar_g_per_100g=nutrients.get(_NUTRIENT_SUGAR),
        sat_fat_g_per_100g=nutrients.get(_NUTRIENT_SAT_FAT),
        cholesterol_mg_per_100g=nutrients.get(_NUTRIENT_CHOLESTEROL),
        sodium_mg_per_100g=nutrients.get(_NUTRIENT_SODIUM),
    )


class UsdaFoodSource(FoodSource):
    source_tag = "usda"

    def __init__(
        self,
        client: httpx.AsyncClient,
        api_key: str,
        base_url: str,
        *,
        data_type: str = DEFAULT_DATA_TYPES,
    ):
        self._client = client
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._data_type = data_type

    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        resp = await self._client.get(
            f"{self._base_url}/foods/search",
            params={
                "api_key": self._api_key,
                "query": query,
                "pageSize": limit,
                "dataType": self._data_type,
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

    async def fetch_portions(self, fdc_id: str) -> list[NormalizedPortion]:
        """Household measures for one food via ``GET /food/{fdcId}`` (detail payload).

        Search responses never include ``foodPortions``, so the service layer calls this
        lazily — once per food, on the first detail request, never per keystroke. Branded
        records have no ``foodPortions``; their household serving text + gram size becomes a
        single synthesized portion when both are present.
        """
        resp = await self._client.get(
            f"{self._base_url}/food/{fdc_id}",
            params={"api_key": self._api_key, "format": "full"},
        )
        resp.raise_for_status()
        payload = resp.json()
        portions = parse_fdc_portions(payload)
        if portions:
            return portions

        household = (payload.get("householdServingFullText") or "").strip()
        serving_size = payload.get("servingSize")
        serving_unit = (payload.get("servingSizeUnit") or "").strip().lower()
        if household and isinstance(serving_size, (int, float)) and serving_size > 0:
            if serving_unit in ("g", "grm", "gram", "grams"):
                return [
                    NormalizedPortion(
                        description=household[:64],
                        gram_weight=float(serving_size),
                        source="usda",
                    )
                ]
        return []

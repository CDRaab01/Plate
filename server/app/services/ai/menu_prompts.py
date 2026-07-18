"""Structured LM Studio parse for restaurant menu text (the voice_prompts precedent).

Turns fetched menu text into categorized build-your-own components. Two nutrition shapes per
component, mirroring how chains publish menus:

* ``official`` — the page states real numbers (Chipotle-style nutrition pages): carried through
  verbatim into an official food.
* ``search_term`` — no numbers on the page (Salsa Grille-style menus): a simple generic term the
  resolver matches against the trusted food search (USDA generic ⇒ estimate).

Like the voice parser the JSON contract is strict in the prompt but the parser is forgiving —
fences, preamble, missing fields, absurd numbers all degrade row-by-row instead of raising. The
result is always an editable draft (never auto-committed, CLAUDE.md §3).
"""

from app.config import settings
from app.services.ai.photo_prompts import _extract_json  # shared forgiving JSON extraction

# Cap components accepted from one menu — guards a runaway model and keeps the editor manageable.
MAX_COMPONENTS = 40

# A published component serving is a few thousand kcal at the absolute most; past this the model
# misread the page (a price, a phone number) and the row degrades to the search path.
_MAX_SANE_KCAL = 5000.0
_MAX_SANE_GRAMS = 5000.0

_LIST_KEYS = ("components", "items", "results")

MENU_SYSTEM_PROMPT = (
    "You convert restaurant menu text into structured data for a food-logging app. You only "
    "output JSON — never prose, never Markdown, never an explanation. You never invent nutrition "
    "numbers: you copy them only when the menu text itself states them."
)

MENU_USER_PROMPT = (
    "From this menu text, extract the build-your-own components a customer picks to compose a "
    "meal (proteins, rices, beans, toppings, salsas, sides). Skip prices, combo deals, and "
    "branded drinks. Respond with ONLY a JSON object (no prose, no code fences) shaped as "
    '{"restaurant_name": string or null, "components": [{"category": string, "name": string, '
    '"search_term": string or null, "typical_grams": number or null, "official": '
    '{"serving_desc": string or null, "serving_grams": number or null, "kcal": number, '
    '"protein_g": number, "carbs_g": number, "fat_g": number} or null}]}. '
    '"category" is the menu section the component belongs to (e.g. "Protein", "Rice", '
    '"Toppings"). Fill "official" ONLY when the text states that component\'s nutrition; '
    'otherwise set it null and give a simple, searchable generic "search_term" (e.g. '
    '"cilantro lime rice", not marketing copy) plus "typical_grams", your estimate of one '
    "typical serving's weight in grams. If the text has no build-your-own components, return "
    '{"restaurant_name": null, "components": []}.\n\nMenu text:\n'
)


def build_menu_messages(menu_text: str) -> list[dict]:
    """Assemble the LM Studio (OpenAI-compatible) message list for one menu."""
    return [
        {"role": "system", "content": MENU_SYSTEM_PROMPT},
        {"role": "user", "content": MENU_USER_PROMPT + menu_text[: settings.menu_parse_max_chars]},
    ]


def _as_component_list(parsed) -> list:
    if isinstance(parsed, list):
        return parsed
    if isinstance(parsed, dict):
        for key in _LIST_KEYS:
            value = parsed.get(key)
            if isinstance(value, list):
                return value
    return []


def _coerce_number(value, *, maximum: float) -> float | None:
    """A sane positive number or None (booleans, junk, and absurd magnitudes all drop)."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    num = float(value)
    return num if 0 < num <= maximum else None


def _clean_official(raw) -> dict | None:
    """Validate an official-nutrition block; None when unusable (row degrades to search)."""
    if not isinstance(raw, dict):
        return None
    kcal = _coerce_number(raw.get("kcal"), maximum=_MAX_SANE_KCAL)
    if kcal is None and raw.get("kcal") != 0:
        return None
    macros = {}
    for field in ("protein_g", "carbs_g", "fat_g"):
        value = raw.get(field)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or float(value) < 0:
            return None
        if float(value) > _MAX_SANE_GRAMS:
            return None
        macros[field] = float(value)
    desc = raw.get("serving_desc")
    return {
        "serving_desc": desc.strip() if isinstance(desc, str) and desc.strip() else None,
        "serving_grams": _coerce_number(raw.get("serving_grams"), maximum=_MAX_SANE_GRAMS),
        "kcal": kcal if kcal is not None else 0.0,
        **macros,
    }


def _clean_component(raw) -> dict | None:
    """Normalize one candidate into ``{category, name, search_term, typical_grams, official}``."""
    if not isinstance(raw, dict):
        return None
    name = raw.get("name") or raw.get("food")
    if not isinstance(name, str) or not name.strip():
        return None
    category = raw.get("category")
    category = category.strip() if isinstance(category, str) and category.strip() else "Menu"
    search_term = raw.get("search_term")
    search_term = (
        search_term.strip() if isinstance(search_term, str) and search_term.strip() else None
    )
    return {
        "category": category[:64],
        "name": name.strip(),
        "search_term": search_term,
        "typical_grams": _coerce_number(raw.get("typical_grams"), maximum=_MAX_SANE_GRAMS),
        "official": _clean_official(raw.get("official")),
    }


def parse_menu_components(raw_reply: str) -> tuple[str | None, list[dict]]:
    """Parse a model reply into ``(restaurant_name, components)``, forgiving by design.

    Returns ``(None, [])`` for empty or unrecoverable output — the caller treats that as
    "nothing recognized on this menu".
    """
    if not raw_reply or not raw_reply.strip():
        return None, []
    parsed = _extract_json(raw_reply)
    if parsed is None:
        return None, []

    restaurant_name = None
    if isinstance(parsed, dict):
        candidate = parsed.get("restaurant_name")
        if isinstance(candidate, str) and candidate.strip():
            restaurant_name = candidate.strip()

    components: list[dict] = []
    for raw in _as_component_list(parsed):
        cleaned = _clean_component(raw)
        if cleaned is not None:
            components.append(cleaned)
        if len(components) >= MAX_COMPONENTS:
            break
    return restaurant_name, components

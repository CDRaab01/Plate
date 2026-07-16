"""Structured LM Studio parse for voice logging (CLAUDE.md §6, §11).

Turns a short spoken utterance ("two eggs and a banana") into a list of ``{food, quantity, unit}``
candidates. Like the photo parser (:mod:`app.services.ai.photo_prompts`), the prompt asks for a
strict JSON contract but the parser is deliberately forgiving — local models wrap JSON in fences, add
preamble, nest the list, or mistype fields — and degrades to an empty list rather than raising. It
does **no** nutrition maths; it only structures the words. Resolving foods → macros happens in
:mod:`app.services.ai.voice` against the trusted food search, and the result is always a
user-confirmed draft (never auto-committed).
"""

import re

from app.services.ai.photo_prompts import _extract_json  # shared forgiving JSON extraction

# Cap how many foods we accept from one utterance — guards against a runaway model and keeps the
# editable draft manageable (mirrors the photo parser's cap).
MAX_ITEMS = 12

# Units we recognize; anything else (spoons, cups, "a", "some", pieces) collapses to "serving", which
# the resolver interprets against the matched food's serving size.
_KNOWN_UNITS = {
    "g": "g",
    "gram": "g",
    "grams": "g",
    "gramme": "g",
    "grammes": "g",
    "oz": "oz",
    "ounce": "oz",
    "ounces": "oz",
    "serving": "serving",
    "servings": "serving",
}

_LIST_KEYS = ("items", "foods", "results")

VOICE_SYSTEM_PROMPT = (
    "You convert a spoken description of a meal into structured data for a food-logging app. You "
    "only output JSON — never prose, never Markdown, never an explanation. You do not estimate "
    "calories or macros; you only identify each food, how much, and the unit."
)

VOICE_USER_PROMPT = (
    "Extract each distinct food the user said they ate from this text. Respond with ONLY a JSON "
    "array (no prose, no code fences) where each element is "
    '{"food": string, "quantity": number, "unit": string}. '
    'Use "g" or "oz" for the unit when a weight is stated; otherwise use "serving" (treat counts '
    'like "two eggs" as quantity 2, unit "serving"). Default quantity to 1 when unspecified. Keep '
    'the food name simple and searchable (e.g. "banana", not "a ripe banana I had"). If there is no '
    "food, return an empty array [].\n\nText: "
)


def build_voice_messages(text: str) -> list[dict]:
    """Assemble the LM Studio (OpenAI-compatible) message list for one utterance."""
    return [
        {"role": "system", "content": VOICE_SYSTEM_PROMPT},
        {"role": "user", "content": VOICE_USER_PROMPT + text.strip()},
    ]


def _as_item_list(parsed) -> list:
    if isinstance(parsed, list):
        return parsed
    if isinstance(parsed, dict):
        for key in _LIST_KEYS:
            value = parsed.get(key)
            if isinstance(value, list):
                return value
        if "food" in parsed or "name" in parsed:
            return [parsed]
    return []


def _coerce_quantity(value) -> float:
    """A positive quantity, defaulting to 1 when missing/unusable (never zero — you ate *something*)."""
    if isinstance(value, bool):
        return 1.0
    if isinstance(value, (int, float)):
        num = float(value)
    elif isinstance(value, str):
        match = re.search(r"\d+(?:\.\d+)?", value)
        num = float(match.group()) if match else 1.0
    else:
        return 1.0
    return num if num > 0 else 1.0


def _normalize_unit(value) -> str:
    if not isinstance(value, str):
        return "serving"
    return _KNOWN_UNITS.get(value.strip().lower(), "serving")


def _clean_item(raw) -> dict | None:
    """Validate + normalize one candidate into ``{food, quantity, unit}`` or ``None`` to drop it."""
    if not isinstance(raw, dict):
        return None
    food = raw.get("food") or raw.get("name")
    if not isinstance(food, str) or not food.strip():
        return None
    return {
        "food": food.strip(),
        "quantity": _coerce_quantity(raw.get("quantity")),
        "unit": _normalize_unit(raw.get("unit")),
    }


def parse_spoken_items(raw_reply: str) -> list[dict]:
    """Parse a model reply into clean ``{food, quantity, unit}`` dicts.

    Forgiving by design (fences/preamble/nested/single-object tolerated); drops anything unusable and
    returns ``[]`` for empty or unrecoverable output — the caller treats that as "nothing recognized".
    """
    if not raw_reply or not raw_reply.strip():
        return []
    parsed = _extract_json(raw_reply)
    if parsed is None:
        return []
    items: list[dict] = []
    for candidate in _as_item_list(parsed):
        cleaned = _clean_item(candidate)
        if cleaned is not None:
            items.append(cleaned)
        if len(items) >= MAX_ITEMS:
            break
    return items

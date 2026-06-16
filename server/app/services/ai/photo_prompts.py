"""Vision prompt + robust output parsing for photo logging (CLAUDE.md §6, §11).

The model is asked for a strict JSON contract, but local models are sloppy: they wrap JSON in
``` fences, add a sentence of preamble, return a single object instead of a list, nest the list under
a key, or omit/mistype fields. :func:`parse_estimate` is deliberately forgiving so a usable estimate
survives all of that, while genuinely unusable output degrades to an empty list (the caller then
tells the user to retake or search manually) — it never raises on bad model text.

These numbers are always an estimate the user confirms before logging; nothing here commits anything.
"""
import json
import re

# Cap how many foods we accept from one photo — guards against a runaway model dumping a huge list,
# and keeps the editable draft manageable.
MAX_ITEMS = 12

# Confidence used when the model omits it: neutral-low, so a fieldless guess doesn't read as certain.
_DEFAULT_CONFIDENCE = 0.3

# Keys a model commonly nests the list of foods under when it ignores "return a bare array".
_LIST_KEYS = ("items", "foods", "results", "estimates", "detected")

VISION_SYSTEM_PROMPT = (
    "You are a nutrition vision assistant for a food-logging app. You look at a photo of a meal and "
    "estimate the distinct foods and their macros. You only output JSON — never prose, never "
    "Markdown, never an explanation."
)

# Strict per-item contract (CLAUDE.md §6). Kept terse so a small local model stays on-format.
VISION_USER_PROMPT = (
    "Identify each distinct food in this photo. Estimate the portion in grams and the macros for "
    "that portion. Respond with ONLY a JSON array, no prose and no code fences, where each element "
    'is an object: {"name": string, "est_grams": number, "kcal": number, "protein_g": number, '
    '"carbs_g": number, "fat_g": number, "confidence": number between 0 and 1}. '
    "If you cannot identify any food, return an empty array []. Do not guess wildly — set a low "
    "confidence when unsure."
)


def build_vision_messages(image_data_url: str) -> list[dict]:
    """Assemble the LM Studio (OpenAI-compatible) message list for a single meal photo.

    The image rides as a ``image_url`` content part with a base64 data URL, which LM Studio's vision
    models accept the same way the OpenAI API does.
    """
    return [
        {"role": "system", "content": VISION_SYSTEM_PROMPT},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": VISION_USER_PROMPT},
                {"type": "image_url", "image_url": {"url": image_data_url}},
            ],
        },
    ]


def _strip_code_fences(text: str) -> str:
    """Remove a surrounding ```/```json fence if the model wrapped its JSON in one."""
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.DOTALL | re.IGNORECASE)
    return fence.group(1).strip() if fence else text.strip()


def _loads_or_none(text: str):
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        return None


def _extract_json(text: str):
    """Best-effort: pull a JSON array/object out of arbitrary model text.

    Tries the whole (de-fenced) string first, then the widest ``[...]`` span, then the widest
    ``{...}`` span — enough to recover JSON buried in a sentence of preamble.
    """
    body = _strip_code_fences(text)
    parsed = _loads_or_none(body)
    if parsed is not None:
        return parsed
    for open_ch, close_ch in (("[", "]"), ("{", "}")):
        start, end = body.find(open_ch), body.rfind(close_ch)
        if start != -1 and end > start:
            parsed = _loads_or_none(body[start : end + 1])
            if parsed is not None:
                return parsed
    return None


def _as_item_list(parsed) -> list:
    """Coerce parsed JSON into a list of candidate item dicts.

    Accepts a bare array, a single object, or an object nesting the array under a common key.
    """
    if isinstance(parsed, list):
        return parsed
    if isinstance(parsed, dict):
        for key in _LIST_KEYS:
            value = parsed.get(key)
            if isinstance(value, list):
                return value
        # A lone object that looks like one food (has a name) is treated as a one-item list.
        if "name" in parsed:
            return [parsed]
    return []


def _coerce_number(value, *, default: float | None = 0.0) -> float | None:
    """Pull a non-negative float out of a model value, or ``default`` when it isn't usable."""
    if isinstance(value, bool):  # bool is an int subclass — reject it explicitly
        return default
    if isinstance(value, (int, float)):
        num = float(value)
    elif isinstance(value, str):
        match = re.search(r"-?\d+(?:\.\d+)?", value)
        if not match:
            return default
        num = float(match.group())
    else:
        return default
    return max(num, 0.0)


def _clean_item(raw) -> dict | None:
    """Validate + normalize one candidate into the strict item shape, or ``None`` to drop it.

    A usable item needs a non-empty name and a numeric kcal; the remaining macros default to 0 and
    confidence is clamped to 0–1 (defaulting when absent)."""
    if not isinstance(raw, dict):
        return None
    name = raw.get("name")
    if not isinstance(name, str) or not name.strip():
        return None

    kcal = _coerce_number(raw.get("kcal"), default=None)
    if kcal is None:
        return None  # no calories → not a usable estimate

    confidence = _coerce_number(raw.get("confidence"), default=_DEFAULT_CONFIDENCE)
    confidence = min(max(confidence if confidence is not None else _DEFAULT_CONFIDENCE, 0.0), 1.0)

    return {
        "name": name.strip(),
        "est_grams": _coerce_number(raw.get("est_grams")) or 0.0,
        "kcal": kcal,
        "protein_g": _coerce_number(raw.get("protein_g")) or 0.0,
        "carbs_g": _coerce_number(raw.get("carbs_g")) or 0.0,
        "fat_g": _coerce_number(raw.get("fat_g")) or 0.0,
        "confidence": confidence,
    }


def parse_estimate(raw_reply: str) -> list[dict]:
    """Parse a model reply into a list of clean estimate dicts (CLAUDE.md §6).

    Forgiving by design: tolerates fences, preamble, single objects, and nested lists, and drops any
    item it can't make sense of. Returns ``[]`` for empty or unrecoverable output — the caller treats
    that as "no food found", never an error. The output is safe to feed straight into
    :class:`~app.schemas.photo.PhotoEstimateItem`.
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

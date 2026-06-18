"""Photo-logging request/response models (CLAUDE.md §6).

The vision model estimates the distinct foods in a meal photo and their macros. The result is always
returned as an **editable draft** the user confirms before anything is logged — these numbers are
estimates, never auto-committed. The strict per-item contract the model is asked to emit is
``{name, est_grams, kcal, protein_g, carbs_g, fat_g, confidence}``; the parser tolerates a lot of
model sloppiness (see :mod:`app.services.ai.photo_prompts`) and only well-formed items reach here.
"""

import uuid

from pydantic import BaseModel, Field


class PhotoEstimateItem(BaseModel):
    """One estimated food in the photo. Macros are for the whole estimated portion (``est_grams``),
    not per 100 g — the client logs the portion as-is (or after the user edits it).

    The model only reliably *identifies* the food and gauges the portion; its recalled macro numbers
    are weak (especially on a small local model). So when the identified name matches a canonical
    food in our DB, the macros here are the **looked-up** values scaled to ``est_grams`` and
    ``matched_food_id``/``matched_name`` point at that row (``source`` is ``usda``/``off``/``user``).
    With no match the macros are the model's own estimate and ``source`` is ``"estimate"``
    (CLAUDE.md §5, §6)."""

    name: str
    est_grams: float = Field(ge=0)
    kcal: float = Field(ge=0)
    protein_g: float = Field(ge=0)
    carbs_g: float = Field(ge=0)
    fat_g: float = Field(ge=0)
    # Model's self-reported confidence, normalized to 0–1. Drives the low-confidence nudge.
    confidence: float = Field(ge=0, le=1)

    # Provenance of the macros above. When a DB food matched the identified name, these point at the
    # canonical row so the client can log it directly (no custom food) and show where the numbers
    # came from. ``source`` is the matched row's source, or ``"estimate"`` for the model's own guess.
    matched_food_id: uuid.UUID | None = None
    matched_name: str | None = None
    source: str = "estimate"


class PhotoEstimateResponse(BaseModel):
    """The editable draft returned to the client. ``items`` may be empty when the model couldn't
    identify any food (or returned unusable output) — in that case ``note`` explains the next step.
    ``low_confidence`` is set when the photo produced no items or any item is below the threshold, so
    the client can prompt the user to refine or fall back to manual search."""

    items: list[PhotoEstimateItem]
    low_confidence: bool
    note: str | None = None

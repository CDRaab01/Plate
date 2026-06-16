"""Photo-logging request/response models (CLAUDE.md §6).

The vision model estimates the distinct foods in a meal photo and their macros. The result is always
returned as an **editable draft** the user confirms before anything is logged — these numbers are
estimates, never auto-committed. The strict per-item contract the model is asked to emit is
``{name, est_grams, kcal, protein_g, carbs_g, fat_g, confidence}``; the parser tolerates a lot of
model sloppiness (see :mod:`app.services.ai.photo_prompts`) and only well-formed items reach here.
"""
from pydantic import BaseModel, Field


class PhotoEstimateItem(BaseModel):
    """One estimated food in the photo. Macros are for the whole estimated portion (``est_grams``),
    not per 100 g — the client logs the portion as-is (or after the user edits it)."""

    name: str
    est_grams: float = Field(ge=0)
    kcal: float = Field(ge=0)
    protein_g: float = Field(ge=0)
    carbs_g: float = Field(ge=0)
    fat_g: float = Field(ge=0)
    # Model's self-reported confidence, normalized to 0–1. Drives the low-confidence nudge.
    confidence: float = Field(ge=0, le=1)


class PhotoEstimateResponse(BaseModel):
    """The editable draft returned to the client. ``items`` may be empty when the model couldn't
    identify any food (or returned unusable output) — in that case ``note`` explains the next step.
    ``low_confidence`` is set when the photo produced no items or any item is below the threshold, so
    the client can prompt the user to refine or fall back to manual search."""

    items: list[PhotoEstimateItem]
    low_confidence: bool
    note: str | None = None

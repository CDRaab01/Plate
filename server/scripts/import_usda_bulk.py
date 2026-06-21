#!/usr/bin/env python3
"""Bulk-import USDA FoodData Central Foundation + SR Legacy datasets.

Downloads the FDC zip exports, normalizes each record with the same logic used
by the live API path, and inserts into the local ``foods`` table in batches.
Idempotent: records already present by ``(source, source_id)`` are skipped, so
the script can be re-run safely after a partial failure or to pick up a new
annual release.

Usage (run from the ``server/`` directory so app imports resolve):

    DATABASE_URL=postgresql+asyncpg://... python scripts/import_usda_bulk.py

Optional env vars:
    FDC_FOUNDATION_URL   Override the Foundation Foods zip download URL
    FDC_SR_LEGACY_URL    Override the SR Legacy zip download URL
    IMPORT_BATCH_SIZE    Rows per INSERT batch (default: 500)

The USDA FDC bulk datasets are released roughly annually at:
    https://fdc.nal.usda.gov/download-foods.html
Foundation Foods and SR Legacy report nutrients per 100 g, which maps directly
to our primary storage basis — no rescaling needed.
"""

import asyncio
import io
import json
import logging
import os
import sys
import uuid
import zipfile
from pathlib import Path

import httpx
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

# Allow running from the server/ directory without installing the package.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.models.food import Food  # noqa: E402 — path fixup must come first

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

_FDC_FOUNDATION_URL = os.getenv(
    "FDC_FOUNDATION_URL",
    "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_foundation_food_json_2026-04-30.zip",
)
_FDC_SR_LEGACY_URL = os.getenv(
    "FDC_SR_LEGACY_URL",
    "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2018-04.zip",
)
_BATCH_SIZE = int(os.getenv("IMPORT_BATCH_SIZE", "500"))

# FDC nutrient numbers — must match app/foods/usda.py
_NUTRIENT_KCAL = "208"
_NUTRIENT_PROTEIN = "203"
_NUTRIENT_FAT = "204"
_NUTRIENT_CARBS = "205"
_NUTRIENT_FIBER = "291"
_NUTRIENT_SUGAR = "269"
_NUTRIENT_SAT_FAT = "606"
_NUTRIENT_CHOLESTEROL = "601"
_NUTRIENT_SODIUM = "307"

_REQUIRED = (_NUTRIENT_KCAL, _NUTRIENT_PROTEIN, _NUTRIENT_CARBS, _NUTRIENT_FAT)


# ---------------------------------------------------------------------------
# Nutrient extraction — bulk JSON uses a different wire format than the search API
# ---------------------------------------------------------------------------

def _bulk_nutrient_map(food: dict) -> dict[str, float]:
    """Index a bulk-format food's ``foodNutrients`` by nutrient number → amount (per 100 g).

    The bulk JSON wraps the nutrient number under ``nutrient.number`` and the
    value under ``amount``, unlike the search API which uses ``nutrientNumber``
    and ``value`` at the top level.  We handle both so this function works
    regardless of which format the caller passes.
    """
    out: dict[str, float] = {}
    for n in food.get("foodNutrients", []) or []:
        # Bulk format: {"nutrient": {"number": "203"}, "amount": 23.1}
        nutrient_obj = n.get("nutrient") or {}
        number = (
            nutrient_obj.get("number")          # bulk format
            or n.get("nutrientNumber")           # search-API format (fallback)
            or n.get("number")                   # other variants
        )
        value = n.get("amount") if "amount" in n else n.get("value")
        if number is None or value is None:
            continue
        out[str(number)] = float(value)
    return out


def _normalize_bulk_food(food: dict) -> dict | None:
    """Map one FDC bulk record to a dict of Food column values, or None if unusable.

    Mirrors the logic in ``app/foods/usda.py:normalize_usda_food`` but handles
    the bulk JSON nutrient format and returns a plain dict ready for SQLAlchemy
    core INSERT (no per-row ORM overhead).
    """
    name = (food.get("description") or "").strip()
    if not name:
        return None

    nutrients = _bulk_nutrient_map(food)
    if any(k not in nutrients for k in _REQUIRED):
        return None

    fdc_id = food.get("fdcId")
    if fdc_id is None:
        return None

    row: dict = {
        "id": uuid.uuid4(),
        "source": "usda",
        "source_id": str(fdc_id),
        "name": name,
        "brand": food.get("brandOwner") or food.get("brandName") or None,
        "barcode": food.get("gtinUpc") or None,
        "serving_size": food.get("servingSize"),
        "serving_unit": food.get("servingSizeUnit"),
        "kcal_per_100g": nutrients[_NUTRIENT_KCAL],
        "protein_g_per_100g": nutrients[_NUTRIENT_PROTEIN],
        "carbs_g_per_100g": nutrients[_NUTRIENT_CARBS],
        "fat_g_per_100g": nutrients[_NUTRIENT_FAT],
        "fiber_g_per_100g": nutrients.get(_NUTRIENT_FIBER),
        "sugar_g_per_100g": nutrients.get(_NUTRIENT_SUGAR),
        "sat_fat_g_per_100g": nutrients.get(_NUTRIENT_SAT_FAT),
        "cholesterol_mg_per_100g": nutrients.get(_NUTRIENT_CHOLESTEROL),
        "sodium_mg_per_100g": nutrients.get(_NUTRIENT_SODIUM),
        # Per-serving fields: Foundation/SR Legacy are per-100g only, no serving defined
        "kcal_per_serving": None,
        "protein_g_per_serving": None,
        "carbs_g_per_serving": None,
        "fat_g_per_serving": None,
        "fiber_g_per_serving": None,
        "sugar_g_per_serving": None,
        "sat_fat_g_per_serving": None,
        "cholesterol_mg_per_serving": None,
        "sodium_mg_per_serving": None,
    }
    return row


# ---------------------------------------------------------------------------
# Download helpers
# ---------------------------------------------------------------------------

def _download_zip(url: str) -> bytes:
    """Stream-download a zip file, logging progress every 10 MB."""
    log.info("Downloading %s", url)
    chunks: list[bytes] = []
    downloaded = 0
    with httpx.stream("GET", url, follow_redirects=True, timeout=300) as resp:
        resp.raise_for_status()
        total = int(resp.headers.get("content-length", 0))
        for chunk in resp.iter_bytes(chunk_size=1024 * 1024):
            chunks.append(chunk)
            downloaded += len(chunk)
            if downloaded % (10 * 1024 * 1024) < len(chunk):
                pct = f"{100 * downloaded // total}%" if total else f"{downloaded // (1024*1024)} MB"
                log.info("  … %s", pct)
    log.info("  Download complete (%d bytes)", downloaded)
    return b"".join(chunks)


def _extract_json(data: bytes) -> dict:
    """Unzip ``data`` in memory and parse the first (only) JSON file inside."""
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        json_files = [n for n in zf.namelist() if n.endswith(".json")]
        if not json_files:
            raise ValueError("No .json file found inside zip")
        name = json_files[0]
        log.info("Parsing %s", name)
        with zf.open(name) as f:
            return json.load(f)


# ---------------------------------------------------------------------------
# Database import
# ---------------------------------------------------------------------------

async def _load_existing_source_ids(engine) -> set[str]:
    """Return all ``source_id`` values already in the DB for source='usda'."""
    async with engine.connect() as conn:
        result = await conn.execute(
            text("SELECT source_id FROM foods WHERE source = 'usda' AND source_id IS NOT NULL")
        )
        return {row[0] for row in result}


async def _insert_batch(engine, rows: list[dict]) -> int:
    """Bulk-insert a batch of food rows; returns count of rows inserted."""
    if not rows:
        return 0
    async with engine.begin() as conn:
        await conn.execute(Food.__table__.insert(), rows)
    return len(rows)


async def _import_dataset(engine, foods: list[dict], dataset_name: str, existing: set[str]) -> int:
    """Normalize, skip known records, and batch-insert a dataset.  Returns inserted count."""
    total = len(foods)
    log.info("%s: %d records to process", dataset_name, total)

    batch: list[dict] = []
    inserted = 0
    skipped_missing_macros = 0
    skipped_existing = 0

    skipped_empty = 0

    for i, raw in enumerate(foods):
        if not isinstance(raw, dict):
            skipped_empty += 1
            continue
        fdc_id = str(raw.get("fdcId", ""))
        if fdc_id in existing:
            skipped_existing += 1
            continue

        row = _normalize_bulk_food(raw)
        if row is None:
            skipped_missing_macros += 1
            continue

        batch.append(row)
        existing.add(fdc_id)  # prevent duplicates across both datasets

        if len(batch) >= _BATCH_SIZE:
            inserted += await _insert_batch(engine, batch)
            batch = []
            log.info(
                "  %s: %d / %d processed, %d inserted so far",
                dataset_name, i + 1, total, inserted,
            )

    if batch:
        inserted += await _insert_batch(engine, batch)

    log.info(
        "%s: done. inserted=%d  skipped_existing=%d  skipped_incomplete=%d  skipped_empty=%d",
        dataset_name, inserted, skipped_existing, skipped_missing_macros, skipped_empty,
    )
    return inserted


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

async def main() -> None:
    db_url = os.environ.get("DATABASE_URL")
    if not db_url:
        # Fall back to the app's settings so running inside the server env works without
        # re-exporting the variable.
        try:
            from app.config import settings  # noqa: PLC0415
            db_url = settings.database_url
        except Exception:
            pass
    if not db_url:
        log.error("DATABASE_URL is not set. Export it before running this script.")
        sys.exit(1)

    engine = create_async_engine(db_url, echo=False, pool_pre_ping=True)

    try:
        log.info("Loading existing USDA source_ids from DB…")
        existing = await _load_existing_source_ids(engine)
        log.info("  %d already imported", len(existing))

        total_inserted = 0

        # Foundation Foods
        raw_zip = _download_zip(_FDC_FOUNDATION_URL)
        payload = _extract_json(raw_zip)
        foundation_foods = payload.get("FoundationFoods", [])
        total_inserted += await _import_dataset(engine, foundation_foods, "Foundation Foods", existing)

        # SR Legacy
        raw_zip = _download_zip(_FDC_SR_LEGACY_URL)
        payload = _extract_json(raw_zip)
        sr_foods = payload.get("SRLegacyFoods", [])
        total_inserted += await _import_dataset(engine, sr_foods, "SR Legacy", existing)

        log.info("Import complete. Total rows inserted: %d", total_inserted)
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())

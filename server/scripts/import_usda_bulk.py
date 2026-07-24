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

import argparse
import asyncio
import datetime
import io
import json
import logging
import os
import sys
import uuid
import zipfile
from collections import Counter
from pathlib import Path

import httpx
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import create_async_engine

# Allow running from the server/ directory without installing the package.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.foods.normalize import resolve_primary_macros  # noqa: E402 — path fixup first
from app.foods.usda import parse_fdc_portions  # noqa: E402
from app.models.food import Food  # noqa: E402
from app.models.food_portion import FoodPortion  # noqa: E402
from app.nutrition.constants import atwater_kcal  # noqa: E402

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

# Energy derivation for records missing a stored 208 uses the shared Atwater helper
# (app/nutrition/constants.atwater_kcal) — the same policy as the live search normalizer.


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


def _nutrients_with_derived_energy(food: dict) -> dict[str, float]:
    """Nutrient map with energy filled via Atwater factors when the record omits a stored value.

    Foundation Foods frequently carries the macro components but no Energy (208); USDA derives
    energy from those components the same way. We only derive on a true miss and only when all
    three macros are present — a published kcal is never overwritten.
    """
    nutrients = _bulk_nutrient_map(food)
    if _NUTRIENT_KCAL not in nutrients and all(
        k in nutrients for k in (_NUTRIENT_PROTEIN, _NUTRIENT_CARBS, _NUTRIENT_FAT)
    ):
        nutrients[_NUTRIENT_KCAL] = atwater_kcal(
            nutrients[_NUTRIENT_PROTEIN],
            nutrients[_NUTRIENT_CARBS],
            nutrients[_NUTRIENT_FAT],
        )
    return nutrients


def _normalize_bulk_food(food: dict) -> tuple[dict, list[dict]] | None:
    """Map one FDC bulk record to Food column values + portion rows, or None if unusable.

    Mirrors the logic in ``app/foods/usda.py:normalize_usda_food`` (including the sparse-macro
    relaxation: zeros imputed + ``macros_incomplete`` flagged when kcal is establishable) but
    handles the bulk JSON nutrient format and returns plain dicts ready for SQLAlchemy core
    INSERT (no per-row ORM overhead). Portion dicts reference the food row's pre-generated id.
    """
    name = (food.get("description") or "").strip()
    if not name:
        return None

    nutrients = _bulk_nutrient_map(food)
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
    if fdc_id is None:
        return None

    food_id = uuid.uuid4()
    serving_label = (food.get("householdServingFullText") or "").strip() or None
    row: dict = {
        "id": food_id,
        "source": "usda",
        "source_id": str(fdc_id),
        "name": name,
        "brand": food.get("brandOwner") or food.get("brandName") or None,
        "barcode": food.get("gtinUpc") or None,
        "serving_size": food.get("servingSize"),
        "serving_unit": food.get("servingSizeUnit"),
        "serving_label": serving_label[:64] if serving_label else None,
        "macros_incomplete": incomplete,
        "created_by": None,
        # The bulk record is the authoritative portion source — never re-fetch from the API.
        "portions_fetched_at": datetime.datetime.now(datetime.timezone.utc),
        "kcal_per_100g": kcal,
        "protein_g_per_100g": protein,
        "carbs_g_per_100g": carbs,
        "fat_g_per_100g": fat,
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
    portion_rows = [
        {"id": uuid.uuid4(), "food_id": food_id, **p.model_dump()}
        for p in parse_fdc_portions(food)
    ]
    return row, portion_rows


def _missing_required(food: dict) -> list[str]:
    """Return the required nutrient numbers absent from ``food`` (empty ⇒ importable).

    Reflects the importer's actual policy: energy is Atwater-derived on a miss, and missing
    single macros are imputed (not skip-worthy) as long as energy is establishable — so a
    record only shows as skipped here when it would really be skipped.
    """
    nutrients = _nutrients_with_derived_energy(food)
    if _NUTRIENT_KCAL in nutrients:
        return []
    return [k for k in _REQUIRED if k not in nutrients]


def _dump_skipped(foods: list[dict], dataset_name: str, sample: int = 5) -> None:
    """Diagnostic: report why records get skipped and dump a few sample records' nutrients.

    Prints, for the dataset, a tally of which required nutrient is missing, then for the
    first ``sample`` skipped records dumps every nutrient they DO carry (number → name :
    amount) so we can see exactly which nutrient numbers the data uses.
    """
    _NUM_TO_NAME = {
        _NUTRIENT_KCAL: "kcal(208)",
        _NUTRIENT_PROTEIN: "protein(203)",
        _NUTRIENT_CARBS: "carbs(205)",
        _NUTRIENT_FAT: "fat(204)",
    }
    missing_tally: Counter = Counter()
    dumped = 0
    for raw in foods:
        if not isinstance(raw, dict):
            continue
        missing = _missing_required(raw)
        if not missing:
            continue
        missing_tally.update(_NUM_TO_NAME.get(m, m) for m in missing)
        if dumped < sample:
            dumped += 1
            name = (raw.get("description") or "?").strip()
            log.info("--- skipped #%d: %s (fdcId=%s)", dumped, name, raw.get("fdcId"))
            log.info("    missing required: %s", [_NUM_TO_NAME.get(m, m) for m in missing])
            for n in raw.get("foodNutrients", []) or []:
                nutrient_obj = n.get("nutrient") or {}
                number = nutrient_obj.get("number") or n.get("nutrientNumber") or n.get("number")
                nname = nutrient_obj.get("name") or n.get("nutrientName") or "?"
                amount = n.get("amount") if "amount" in n else n.get("value")
                log.info("      [%s] %s = %s", number, nname, amount)

    log.info("%s: missing-required tally across all skipped: %s", dataset_name, dict(missing_tally))


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


async def _insert_batch(engine, rows: list[dict], portion_rows: list[dict]) -> int:
    """Bulk-insert a batch of food rows + their portions; returns count of foods inserted."""
    if not rows:
        return 0
    async with engine.begin() as conn:
        await conn.execute(Food.__table__.insert(), rows)
        if portion_rows:
            await conn.execute(
                pg_insert(FoodPortion.__table__).on_conflict_do_nothing(
                    constraint="uq_food_portions_food_desc"
                ),
                portion_rows,
            )
    return len(rows)


async def _import_dataset(engine, foods: list[dict], dataset_name: str, existing: set[str]) -> int:
    """Normalize, skip known records, and batch-insert a dataset.  Returns inserted count."""
    total = len(foods)
    log.info("%s: %d records to process", dataset_name, total)

    batch: list[dict] = []
    portion_batch: list[dict] = []
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

        normalized = _normalize_bulk_food(raw)
        if normalized is None:
            skipped_missing_macros += 1
            continue
        row, portion_rows = normalized

        batch.append(row)
        portion_batch.extend(portion_rows)
        existing.add(fdc_id)  # prevent duplicates across both datasets

        if len(batch) >= _BATCH_SIZE:
            inserted += await _insert_batch(engine, batch, portion_batch)
            batch = []
            portion_batch = []
            log.info(
                "  %s: %d / %d processed, %d inserted so far",
                dataset_name, i + 1, total, inserted,
            )

    if batch:
        inserted += await _insert_batch(engine, batch, portion_batch)

    log.info(
        "%s: done. inserted=%d  skipped_existing=%d  skipped_incomplete=%d  skipped_empty=%d",
        dataset_name, inserted, skipped_existing, skipped_missing_macros, skipped_empty,
    )
    return inserted


async def _backfill_portions(engine, foods: list[dict], dataset_name: str) -> int:
    """Insert portions for already-imported ``source='usda'`` rows that predate the portions
    feature (the import skips existing records wholesale, so a pre-0007 catalog never gets
    portions without this). Idempotent: existing (food_id, description) pairs are left alone,
    and every matched food is stamped ``portions_fetched_at`` so the live detail path never
    re-fetches what the bulk data already answered.
    """
    async with engine.connect() as conn:
        result = await conn.execute(
            text("SELECT source_id, id FROM foods WHERE source = 'usda' AND source_id IS NOT NULL")
        )
        id_by_source: dict[str, uuid.UUID] = {row[0]: row[1] for row in result}

    portion_rows: list[dict] = []
    matched_food_ids: list[uuid.UUID] = []
    for raw in foods:
        if not isinstance(raw, dict):
            continue
        food_id = id_by_source.get(str(raw.get("fdcId", "")))
        if food_id is None:
            continue
        matched_food_ids.append(food_id)
        portion_rows.extend(
            {"id": uuid.uuid4(), "food_id": food_id, **p.model_dump()}
            for p in parse_fdc_portions(raw)
        )

    now = datetime.datetime.now(datetime.timezone.utc)
    inserted = 0
    for start in range(0, len(portion_rows), _BATCH_SIZE):
        chunk = portion_rows[start : start + _BATCH_SIZE]
        async with engine.begin() as conn:
            result = await conn.execute(
                pg_insert(FoodPortion.__table__).on_conflict_do_nothing(
                    constraint="uq_food_portions_food_desc"
                ),
                chunk,
            )
        inserted += result.rowcount or 0
    for start in range(0, len(matched_food_ids), _BATCH_SIZE):
        chunk = matched_food_ids[start : start + _BATCH_SIZE]
        async with engine.begin() as conn:
            await conn.execute(
                text(
                    "UPDATE foods SET portions_fetched_at = :now"
                    " WHERE id = ANY(:ids) AND portions_fetched_at IS NULL"
                ),
                {"now": now, "ids": chunk},
            )
    log.info(
        "%s: backfill matched %d foods, inserted %d portion rows",
        dataset_name, len(matched_food_ids), inserted,
    )
    return inserted


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def _run_dump_skipped() -> None:
    """Download Foundation + SR Legacy and print skip diagnostics — no DB access."""
    raw_zip = _download_zip(_FDC_FOUNDATION_URL)
    payload = _extract_json(raw_zip)
    _dump_skipped(payload.get("FoundationFoods", []), "Foundation Foods")

    raw_zip = _download_zip(_FDC_SR_LEGACY_URL)
    payload = _extract_json(raw_zip)
    _dump_skipped(payload.get("SRLegacyFoods", []), "SR Legacy")


def _dump_portions(foods: list[dict], dataset_name: str, sample: int = 20) -> None:
    """Diagnostic: print the parsed portions for the first ``sample`` records that have any,
    plus a dataset-wide tally — verify the parser against real data before a full run."""
    with_portions = 0
    total_portions = 0
    dumped = 0
    for raw in foods:
        if not isinstance(raw, dict):
            continue
        portions = parse_fdc_portions(raw)
        if not portions:
            continue
        with_portions += 1
        total_portions += len(portions)
        if dumped < sample:
            dumped += 1
            name = (raw.get("description") or "?").strip()
            log.info("--- %s (fdcId=%s)", name, raw.get("fdcId"))
            for p in portions:
                log.info("      %-40s = %g g", p.description, p.gram_weight)
    log.info(
        "%s: %d/%d records have portions (%d portion rows total)",
        dataset_name, with_portions, len(foods), total_portions,
    )


def _run_dump_portions() -> None:
    """Download Foundation + SR Legacy and print parsed-portion diagnostics — no DB access."""
    raw_zip = _download_zip(_FDC_FOUNDATION_URL)
    payload = _extract_json(raw_zip)
    _dump_portions(payload.get("FoundationFoods", []), "Foundation Foods")

    raw_zip = _download_zip(_FDC_SR_LEGACY_URL)
    payload = _extract_json(raw_zip)
    _dump_portions(payload.get("SRLegacyFoods", []), "SR Legacy")


async def main(*, backfill_portions: bool = False) -> None:
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
        if backfill_portions:
            raw_zip = _download_zip(_FDC_FOUNDATION_URL)
            payload = _extract_json(raw_zip)
            total = await _backfill_portions(
                engine, payload.get("FoundationFoods", []), "Foundation Foods"
            )

            raw_zip = _download_zip(_FDC_SR_LEGACY_URL)
            payload = _extract_json(raw_zip)
            total += await _backfill_portions(engine, payload.get("SRLegacyFoods", []), "SR Legacy")

            log.info("Portion backfill complete. Total portion rows inserted: %d", total)
            return

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
    parser = argparse.ArgumentParser(description="Import USDA FDC bulk datasets into the foods table.")
    parser.add_argument(
        "--dump-skipped",
        action="store_true",
        help="Diagnostic only: download the datasets and print why records get skipped "
        "(which required nutrient is missing + a dump of sample records). No DB writes.",
    )
    parser.add_argument(
        "--dump-portions",
        action="store_true",
        help="Diagnostic only: download the datasets and print the household portions the "
        "parser extracts (sample per dataset + tally). No DB writes.",
    )
    parser.add_argument(
        "--backfill-portions",
        action="store_true",
        help="Insert portions for already-imported USDA rows (a catalog imported before the "
        "portions feature never gets them otherwise). Idempotent; no new foods are added.",
    )
    args = parser.parse_args()

    if args.dump_skipped:
        _run_dump_skipped()
    elif args.dump_portions:
        _run_dump_portions()
    else:
        asyncio.run(main(backfill_portions=args.backfill_portions))

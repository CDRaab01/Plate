"""Food sourcing tests (CLAUDE.md §5, §10).

Covers USDA/OFF normalization (with **mocked** HTTP — no live calls), the local-cache-first
search strategy, and deduplication by barcode and normalized name. External providers are
exercised either through ``httpx.MockTransport`` or via in-memory fake sources.
"""
import uuid

import httpx
import pytest

from app.database import AsyncSessionLocal
from app.foods.base import FoodSource
from app.foods.normalize import NormalizedFood, normalized_name
from app.foods.off import OpenFoodFactsSource, normalize_off_product
from app.foods.usda import UsdaFoodSource, normalize_usda_food
from app.models.food import Food
from app.services.food_service import cache_foods, lookup_barcode, search_foods


# ── Normalization ────────────────────────────────────────────────────────────


def test_normalized_name_folds_case_and_whitespace():
    assert normalized_name("  Chicken   Breast ") == "chicken breast"


def test_normalize_usda_maps_nutrient_numbers():
    raw = {
        "fdcId": 12345,
        "description": "Chicken breast, raw",
        "brandOwner": "Generic",
        "servingSize": 140.0,
        "servingSizeUnit": "g",
        "foodNutrients": [
            {"nutrientNumber": "208", "value": 165.0},
            {"nutrientNumber": "203", "value": 31.0},
            {"nutrientNumber": "205", "value": 0.0},
            {"nutrientNumber": "204", "value": 3.6},
            {"nutrientNumber": "601", "value": 85.0},  # cholesterol already mg
            {"nutrientNumber": "307", "value": 74.0},  # sodium already mg
        ],
    }
    food = normalize_usda_food(raw)
    assert food is not None
    assert food.source == "usda"
    assert food.source_id == "12345"
    assert food.kcal_per_100g == 165.0
    assert food.protein_g_per_100g == 31.0
    assert food.cholesterol_mg_per_100g == 85.0
    assert food.sodium_mg_per_100g == 74.0
    assert food.serving_size == 140.0


def test_normalize_usda_skips_records_missing_primary_macros():
    raw = {"fdcId": 1, "description": "Mystery", "foodNutrients": [{"nutrientNumber": "208", "value": 100.0}]}
    assert normalize_usda_food(raw) is None


def test_normalize_off_converts_grams_to_mg():
    product = {
        "code": "3017620422003",
        "product_name": "Nutella",
        "brands": "Ferrero, Nutella",
        "serving_quantity": 15.0,
        "nutriments": {
            "energy-kcal_100g": 539,
            "proteins_100g": 6.3,
            "carbohydrates_100g": 57.5,
            "fat_100g": 30.9,
            "sugars_100g": 56.3,
            "sodium_100g": 0.0428,  # grams → 42.8 mg
            "cholesterol_100g": 0.01,  # grams → 10 mg
        },
    }
    food = normalize_off_product(product)
    assert food is not None
    assert food.source == "off"
    assert food.barcode == "3017620422003"
    assert food.brand == "Ferrero"  # first brand only
    assert food.sodium_mg_per_100g == pytest.approx(42.8)
    assert food.cholesterol_mg_per_100g == pytest.approx(10.0)
    assert food.serving_size == 15.0


def test_normalize_off_skips_missing_macros():
    product = {"code": "1", "product_name": "Unknown", "nutriments": {"energy-kcal_100g": 100}}
    assert normalize_off_product(product) is None


async def test_usda_source_search_with_mocked_http():
    def handler(request: httpx.Request) -> httpx.Response:
        assert "foods/search" in str(request.url)
        return httpx.Response(
            200,
            json={
                "foods": [
                    {
                        "fdcId": 1,
                        "description": "Banana, raw",
                        "foodNutrients": [
                            {"nutrientNumber": "208", "value": 89.0},
                            {"nutrientNumber": "203", "value": 1.1},
                            {"nutrientNumber": "205", "value": 22.8},
                            {"nutrientNumber": "204", "value": 0.3},
                        ],
                    }
                ]
            },
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        source = UsdaFoodSource(client, "fake-key", "https://api.example.gov/fdc/v1")
        results = await source.search("banana", limit=5)
    assert len(results) == 1
    assert results[0].name == "Banana, raw"


async def test_off_source_search_with_mocked_http():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["User-Agent"].startswith("Plate/")
        return httpx.Response(
            200,
            json={
                "products": [
                    {
                        "code": "111",
                        "product_name": "Greek Yogurt",
                        "brands": "Fage",
                        "nutriments": {
                            "energy-kcal_100g": 97,
                            "proteins_100g": 9,
                            "carbohydrates_100g": 3.6,
                            "fat_100g": 5,
                        },
                    }
                ]
            },
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        source = OpenFoodFactsSource(client, "https://off.example.org", "Plate/0.1.0 (test@test)")
        results = await source.search("yogurt", limit=5)
    assert len(results) == 1
    assert results[0].barcode == "111"


async def test_off_fetch_barcode_with_mocked_http():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "status": 1,
                "product": {
                    "code": "222",
                    "product_name": "Oat Milk",
                    "nutriments": {
                        "energy-kcal_100g": 46,
                        "proteins_100g": 1,
                        "carbohydrates_100g": 6.7,
                        "fat_100g": 1.5,
                    },
                },
            },
        )

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        source = OpenFoodFactsSource(client, "https://off.example.org", "Plate/0.1.0 (test@test)")
        food = await source.fetch_barcode("222")
    assert food is not None
    assert food.name == "Oat Milk"


# ── Cache-first search + dedup ───────────────────────────────────────────────


class FakeSource(FoodSource):
    def __init__(self, tag: str, items: list[NormalizedFood]):
        self.source_tag = tag
        self._items = items
        self.calls = 0

    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        self.calls += 1
        return self._items


def _normalized(name: str, *, barcode: str | None = None, source: str = "off") -> NormalizedFood:
    return NormalizedFood(
        source=source,
        source_id=barcode,
        name=name,
        barcode=barcode,
        kcal_per_100g=100.0,
        protein_g_per_100g=5.0,
        carbs_g_per_100g=10.0,
        fat_g_per_100g=2.0,
    )


async def test_search_returns_local_without_calling_sources():
    unique = f"Localberry {uuid.uuid4().hex[:6]}"
    async with AsyncSessionLocal() as db:
        db.add(
            Food(
                source="user",
                name=unique,
                kcal_per_100g=10.0,
                protein_g_per_100g=1.0,
                carbs_g_per_100g=2.0,
                fat_g_per_100g=0.0,
            )
        )
        await db.commit()

    source = FakeSource("off", [_normalized("Should not be used")])
    async with AsyncSessionLocal() as db:
        results = await search_foods(db, unique, sources=[source])
    assert source.calls == 0
    assert any(f.name == unique for f in results)


async def test_search_caches_external_results_on_miss():
    query = f"Quinoa {uuid.uuid4().hex[:6]}"
    item = _normalized(query, barcode=uuid.uuid4().hex)
    source = FakeSource("off", [item])

    async with AsyncSessionLocal() as db:
        first = await search_foods(db, query, sources=[source])
    assert source.calls == 1
    assert len(first) == 1
    assert first[0].id is not None  # persisted

    # Second search finds it locally — the source is not hit again.
    async with AsyncSessionLocal() as db:
        second = await search_foods(db, query, sources=[source])
    assert source.calls == 1
    assert second[0].name == query


async def test_cache_dedups_within_batch_by_barcode():
    barcode = uuid.uuid4().hex
    name = f"DupFood {uuid.uuid4().hex[:6]}"
    items = [_normalized(name, barcode=barcode), _normalized(name + " (regional)", barcode=barcode)]
    async with AsyncSessionLocal() as db:
        cached = await cache_foods(db, items)
    assert len(cached) == 1


async def test_cache_dedups_against_existing_db_row_by_name():
    name = f"Spinach {uuid.uuid4().hex[:6]}"
    async with AsyncSessionLocal() as db:
        first = await cache_foods(db, [_normalized(name, source="usda")])
        first_id = first[0].id

    async with AsyncSessionLocal() as db:
        # Same normalized name from a different source must reuse the existing row.
        second = await cache_foods(db, [_normalized(name.upper(), source="off")])
    assert second[0].id == first_id


async def test_blank_query_returns_empty():
    async with AsyncSessionLocal() as db:
        assert await search_foods(db, "   ", sources=[FakeSource("off", [])]) == []


# ── Barcode scan path (Phase 4) ──────────────────────────────────────────────


class FakeBarcodeSource(FoodSource):
    """OFF-like source that only answers barcode lookups (the Phase 4 scan path)."""

    source_tag = "off"

    def __init__(self, product: NormalizedFood | None):
        self._product = product
        self.calls = 0

    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        return []

    async def fetch_barcode(self, barcode: str) -> NormalizedFood | None:
        self.calls += 1
        return self._product


async def test_lookup_barcode_fetches_and_caches_on_miss():
    barcode = uuid.uuid4().hex
    name = f"Scanned Bar {uuid.uuid4().hex[:6]}"
    source = FakeBarcodeSource(_normalized(name, barcode=barcode))

    async with AsyncSessionLocal() as db:
        first = await lookup_barcode(db, barcode, source=source)
    assert first is not None
    assert first.barcode == barcode
    assert first.id is not None  # persisted
    assert source.calls == 1

    # Second scan of the same code is served from the local cache — OFF is not hit again.
    async with AsyncSessionLocal() as db:
        second = await lookup_barcode(db, barcode, source=source)
    assert second is not None
    assert second.id == first.id
    assert source.calls == 1


async def test_lookup_barcode_returns_none_when_off_has_no_product():
    source = FakeBarcodeSource(None)
    async with AsyncSessionLocal() as db:
        result = await lookup_barcode(db, uuid.uuid4().hex, source=source)
    assert result is None
    assert source.calls == 1


async def test_lookup_barcode_local_hit_skips_source():
    barcode = uuid.uuid4().hex
    async with AsyncSessionLocal() as db:
        db.add(
            Food(
                source="off",
                name=f"Cached Bar {uuid.uuid4().hex[:6]}",
                barcode=barcode,
                kcal_per_100g=50.0,
                protein_g_per_100g=1.0,
                carbs_g_per_100g=10.0,
                fat_g_per_100g=0.0,
            )
        )
        await db.commit()

    source = FakeBarcodeSource(_normalized("Should not be used", barcode=barcode))
    async with AsyncSessionLocal() as db:
        found = await lookup_barcode(db, barcode, source=source)
    assert found is not None
    assert found.barcode == barcode
    assert source.calls == 0


async def test_lookup_barcode_blank_returns_none():
    async with AsyncSessionLocal() as db:
        assert await lookup_barcode(db, "   ", source=FakeBarcodeSource(None)) is None


async def test_barcode_endpoint_unknown_returns_404(auth_client):
    # Live lookups are disabled in the suite, so an uncached barcode resolves to nothing → 404.
    resp = await auth_client.get(f"/foods/barcode/{uuid.uuid4().hex}")
    assert resp.status_code == 404


async def test_barcode_endpoint_returns_cached_food(auth_client):
    barcode = uuid.uuid4().hex
    name = f"Endpoint Bar {uuid.uuid4().hex[:6]}"
    async with AsyncSessionLocal() as db:
        db.add(
            Food(
                source="off",
                name=name,
                barcode=barcode,
                kcal_per_100g=120.0,
                protein_g_per_100g=3.0,
                carbs_g_per_100g=20.0,
                fat_g_per_100g=2.0,
            )
        )
        await db.commit()

    resp = await auth_client.get(f"/foods/barcode/{barcode}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["barcode"] == barcode
    assert body["name"] == name


async def test_barcode_endpoint_requires_auth(client):
    resp = await client.get(f"/foods/barcode/{uuid.uuid4().hex}")
    assert resp.status_code == 401

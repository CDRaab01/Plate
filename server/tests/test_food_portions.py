"""Named-portion tests: FDC/OFF portion parsing, caching, the detail endpoint's lazy USDA
enrichment, and logging by portion (CLAUDE.md §5).

Pure parsers are table-driven; external HTTP is exercised via ``httpx.MockTransport`` only —
no live calls (CLAUDE.md §10).
"""
import uuid

import httpx
import pytest
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.foods.normalize import NormalizedFood, NormalizedPortion, resolve_primary_macros
from app.foods.off import normalize_off_product
from app.foods.usda import UsdaFoodSource, parse_fdc_portions
from app.models.food import Food
from app.models.food_portion import FoodPortion
from app.nutrition.constants import atwater_kcal
from app.nutrition.portions import portion_grams
from app.services.food_service import cache_foods, get_food_detail


# ── Pure helpers ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "protein,carbs,fat,kcal",
    [(0.0, 0.0, 0.0, 0.0), (10.0, 20.0, 5.0, 165.0), (31.0, 0.0, 3.6, 156.4)],
)
def test_atwater_kcal_table(protein, carbs, fat, kcal):
    assert atwater_kcal(protein, carbs, fat) == pytest.approx(kcal)


@pytest.mark.parametrize(
    "quantity,gram_weight,grams",
    [(1.0, 240.0, 240.0), (2.0, 28.0, 56.0), (0.5, 240.0, 120.0)],
)
def test_portion_grams_table(quantity, gram_weight, grams):
    assert portion_grams(quantity, gram_weight) == pytest.approx(grams)


@pytest.mark.parametrize("quantity,gram_weight", [(0.0, 240.0), (-1.0, 240.0), (1.0, 0.0)])
def test_portion_grams_rejects_non_positive(quantity, gram_weight):
    with pytest.raises(ValueError):
        portion_grams(quantity, gram_weight)


def test_resolve_primary_macros_policy_table():
    # All present → unchanged, complete.
    assert resolve_primary_macros(100.0, 10.0, 5.0, 2.0) == (100.0, 10.0, 5.0, 2.0, False)
    # kcal missing, macros complete → Atwater, complete.
    kcal, *_rest, incomplete = resolve_primary_macros(None, 10.0, 20.0, 5.0)
    assert kcal == pytest.approx(165.0)
    assert incomplete is False
    # kcal present, a macro missing → zero imputed + flagged.
    assert resolve_primary_macros(100.0, None, 5.0, 2.0) == (100.0, 0.0, 5.0, 2.0, True)
    # Energy not establishable → dropped.
    assert resolve_primary_macros(None, 10.0, None, 2.0) is None


# ── FDC foodPortions parsing (detail / bulk shape) ───────────────────────────


def test_parse_fdc_portions_sr_legacy_description_shape():
    food = {
        "foodPortions": [
            {
                "sequenceNumber": 2,
                "gramWeight": 28.35,
                "portionDescription": "1 oz",
            },
            {
                "sequenceNumber": 1,
                "gramWeight": 240.0,
                "portionDescription": "1 cup, diced",
            },
        ]
    }
    portions = parse_fdc_portions(food)
    assert [(p.description, p.gram_weight) for p in portions] == [
        ("1 cup, diced", 240.0),
        ("1 oz", 28.35),
    ]
    assert [p.sort_order for p in portions] == [0, 1]
    assert all(p.source == "usda" for p in portions)


def test_parse_fdc_portions_foundation_modifier_shape():
    # Foundation: measureUnit is "undetermined", the real measure lives in `modifier`.
    food = {
        "foodPortions": [
            {
                "sequenceNumber": 1,
                "amount": 1.0,
                "gramWeight": 118.0,
                "measureUnit": {"name": "undetermined"},
                "modifier": "cup, sliced",
            }
        ]
    }
    portions = parse_fdc_portions(food)
    assert [(p.description, p.gram_weight) for p in portions] == [("1 cup, sliced", 118.0)]


def test_parse_fdc_portions_measure_unit_with_modifier_qualifier():
    food = {
        "foodPortions": [
            {
                "amount": 1.0,
                "gramWeight": 85.0,
                "measureUnit": {"name": "cup"},
                "modifier": "shredded",
            }
        ]
    }
    portions = parse_fdc_portions(food)
    assert portions[0].description == "1 cup, shredded"


def test_parse_fdc_portions_skips_unusable_rows():
    food = {
        "foodPortions": [
            {"gramWeight": 100.0, "portionDescription": "Quantity not specified"},
            {"gramWeight": 0.0, "portionDescription": "1 cup"},  # non-positive grams
            {"portionDescription": "1 cup"},  # no gramWeight
            {"gramWeight": 50.0, "measureUnit": {"name": "undetermined"}},  # no usable label
        ]
    }
    assert parse_fdc_portions(food) == []


def test_parse_fdc_portions_dedupes_and_caps():
    rows = [
        {"sequenceNumber": i, "gramWeight": 10.0 + i, "portionDescription": f"portion {i}"}
        for i in range(12)
    ]
    rows.append({"sequenceNumber": 99, "gramWeight": 5.0, "portionDescription": "Portion 0"})
    portions = parse_fdc_portions({"foodPortions": rows})
    assert len(portions) == 8  # capped
    descriptions = [p.description for p in portions]
    assert len({d.lower() for d in descriptions}) == len(descriptions)  # deduped


# ── OFF serving-label parsing ────────────────────────────────────────────────


def _off_product(**overrides) -> dict:
    base = {
        "code": uuid.uuid4().hex,
        "product_name": "Cookies",
        "serving_size": "30 g (2 cookies)",
        "serving_quantity": 30.0,
        "nutriments": {
            "energy-kcal_100g": 480,
            "proteins_100g": 6.0,
            "carbohydrates_100g": 60.0,
            "fat_100g": 22.0,
        },
    }
    base.update(overrides)
    return base


def test_off_portion_from_parenthetical_label():
    food = normalize_off_product(_off_product())
    assert food.serving_label == "30 g (2 cookies)"
    assert [(p.description, p.gram_weight) for p in food.portions] == [("2 cookies", 30.0)]
    assert food.portions[0].source == "off"


def test_off_portion_without_parenthetical_uses_whole_label():
    food = normalize_off_product(_off_product(serving_size="1 bar", serving_quantity=45.0))
    assert [(p.description, p.gram_weight) for p in food.portions] == [("1 bar", 45.0)]


def test_off_no_portion_without_serving_quantity():
    food = normalize_off_product(_off_product(serving_size="a handful", serving_quantity=None))
    assert food.portions == []
    assert food.serving_label == "a handful"


# ── Caching portions ─────────────────────────────────────────────────────────


def _normalized_with_portion(name: str, *, barcode: str | None = None) -> NormalizedFood:
    return NormalizedFood(
        source="off",
        source_id=barcode,
        name=name,
        barcode=barcode,
        kcal_per_100g=100.0,
        protein_g_per_100g=5.0,
        carbs_g_per_100g=10.0,
        fat_g_per_100g=2.0,
        portions=[NormalizedPortion(description="2 cookies", gram_weight=30.0, source="off")],
    )


async def test_cache_foods_persists_portions():
    item = _normalized_with_portion(f"Portioned {uuid.uuid4().hex[:6]}", barcode=uuid.uuid4().hex)
    async with AsyncSessionLocal() as db:
        cached = await cache_foods(db, [item])
        food = await get_food_detail(db, cached[0].id)
        assert [(p.description, p.gram_weight) for p in food.portions] == [("2 cookies", 30.0)]


async def test_cache_foods_heals_existing_portionless_row():
    barcode = uuid.uuid4().hex
    name = f"Healable {uuid.uuid4().hex[:6]}"
    async with AsyncSessionLocal() as db:
        db.add(
            Food(
                source="off",
                source_id=barcode,
                barcode=barcode,
                name=name,
                kcal_per_100g=100.0,
                protein_g_per_100g=5.0,
                carbs_g_per_100g=10.0,
                fat_g_per_100g=2.0,
            )
        )
        await db.commit()

    async with AsyncSessionLocal() as db:
        cached = await cache_foods(db, [_normalized_with_portion(name, barcode=barcode)])
        food = await get_food_detail(db, cached[0].id)
        assert len(food.portions) == 1  # attached to the pre-existing row, not a duplicate food


# ── Lazy USDA enrichment on the detail path ──────────────────────────────────


def _usda_detail_source(calls: list) -> UsdaFoodSource:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(
            200,
            json={
                "fdcId": 999,
                "foodPortions": [
                    {"sequenceNumber": 1, "gramWeight": 240.0, "portionDescription": "1 cup"}
                ],
            },
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return UsdaFoodSource(client, "fake-key", "https://api.example.gov/fdc/v1")


async def test_detail_lazily_fetches_usda_portions_once():
    calls: list = []
    source = _usda_detail_source(calls)
    async with AsyncSessionLocal() as db:
        food = Food(
            source="usda",
            source_id="999",
            name=f"Bananas {uuid.uuid4().hex[:6]}",
            kcal_per_100g=89.0,
            protein_g_per_100g=1.1,
            carbs_g_per_100g=22.8,
            fat_g_per_100g=0.3,
        )
        db.add(food)
        await db.commit()
        food_id = food.id

    async with AsyncSessionLocal() as db:
        detail = await get_food_detail(db, food_id, usda_source=source)
        assert [(p.description, p.gram_weight) for p in detail.portions] == [("1 cup", 240.0)]
        assert detail.portions_fetched_at is not None
    assert len(calls) == 1
    assert "food/999" in calls[0]

    # Second detail request: portions cached, marker stamped → no re-fetch.
    async with AsyncSessionLocal() as db:
        detail = await get_food_detail(db, food_id, usda_source=source)
        assert len(detail.portions) == 1
    assert len(calls) == 1


async def test_detail_transport_failure_leaves_marker_null_for_retry():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom")

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    source = UsdaFoodSource(client, "fake-key", "https://api.example.gov/fdc/v1")

    async with AsyncSessionLocal() as db:
        food = Food(
            source="usda",
            source_id="1000",
            name=f"Apples {uuid.uuid4().hex[:6]}",
            kcal_per_100g=52.0,
            protein_g_per_100g=0.3,
            carbs_g_per_100g=13.8,
            fat_g_per_100g=0.2,
        )
        db.add(food)
        await db.commit()
        food_id = food.id

    async with AsyncSessionLocal() as db:
        detail = await get_food_detail(db, food_id, usda_source=source)
        assert detail is not None
        assert detail.portions == []
        assert detail.portions_fetched_at is None  # NULL ⇒ a later tap retries


async def test_usda_branded_detail_falls_back_to_household_serving():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "fdcId": 42,
                "householdServingFullText": "2 cookies",
                "servingSize": 30.0,
                "servingSizeUnit": "g",
            },
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    source = UsdaFoodSource(client, "fake-key", "https://api.example.gov/fdc/v1")
    portions = await source.fetch_portions("42")
    assert [(p.description, p.gram_weight) for p in portions] == [("2 cookies", 30.0)]


# ── Detail endpoint ──────────────────────────────────────────────────────────


async def test_food_detail_endpoint_returns_portions(auth_client):
    async with AsyncSessionLocal() as db:
        food = Food(
            source="user",
            name=f"Detailed {uuid.uuid4().hex[:6]}",
            kcal_per_100g=100.0,
            protein_g_per_100g=5.0,
            carbs_g_per_100g=10.0,
            fat_g_per_100g=2.0,
            serving_label="1 cup (240 g)",
        )
        food.portions = [FoodPortion(description="1 cup", gram_weight=240.0, source="user")]
        db.add(food)
        await db.commit()
        food_id = food.id

    resp = await auth_client.get(f"/foods/{food_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["serving_label"] == "1 cup (240 g)"
    assert body["macros_incomplete"] is False
    assert [(p["description"], p["gram_weight"]) for p in body["portions"]] == [("1 cup", 240.0)]


# ── Logging by portion ───────────────────────────────────────────────────────


async def _food_with_portion(description: str = "1 cup, sliced", gram_weight: float = 240.0):
    async with AsyncSessionLocal() as db:
        food = Food(
            source="user",
            name=f"Portion Banana {uuid.uuid4().hex[:6]}",
            kcal_per_100g=89.0,
            protein_g_per_100g=1.1,
            carbs_g_per_100g=22.8,
            fat_g_per_100g=0.3,
        )
        food.portions = [
            FoodPortion(description=description, gram_weight=gram_weight, source="usda")
        ]
        db.add(food)
        await db.commit()
        await db.refresh(food)
        portion_id = (
            (await db.execute(select(FoodPortion.id).where(FoodPortion.food_id == food.id)))
            .scalars()
            .first()
        )
        return food, portion_id


async def test_log_by_portion_snapshots_from_gram_weight(auth_client):
    food, portion_id = await _food_with_portion()
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),
            "date": "2026-07-24",
            "meal": "lunch",
            "quantity": 2.0,
            "unit": "serving",  # ignored — portion_id wins
            "portion_id": str(portion_id),
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    # 2 × 240 g = 480 g of an 89 kcal/100g food.
    assert body["kcal"] == pytest.approx(89.0 * 4.8)
    assert body["quantity"] == 2.0
    assert body["unit"] == "1 cup, sliced"


async def test_log_by_portion_rejects_foreign_portion(auth_client, food):
    _other_food, portion_id = await _food_with_portion()
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),  # portion belongs to a different food
            "date": "2026-07-24",
            "meal": "lunch",
            "quantity": 1.0,
            "unit": "serving",
            "portion_id": str(portion_id),
        },
    )
    assert resp.status_code == 400


async def test_log_by_portion_truncates_long_label(auth_client):
    long_label = "1 extra large restaurant-style serving bowl, heaping full"  # > 32 chars
    food, portion_id = await _food_with_portion(description=long_label, gram_weight=400.0)
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),
            "date": "2026-07-24",
            "meal": "dinner",
            "quantity": 1.0,
            "unit": "serving",
            "portion_id": str(portion_id),
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["unit"] == long_label[:32]


async def test_portion_entry_quantity_edit_rescales(auth_client):
    food, portion_id = await _food_with_portion()
    created = (
        await auth_client.post(
            "/log",
            json={
                "food_id": str(food.id),
                "date": "2026-07-24",
                "meal": "lunch",
                "quantity": 1.0,
                "unit": "serving",
                "portion_id": str(portion_id),
            },
        )
    ).json()

    resp = await auth_client.put(f"/log/{created['id']}", json={"quantity": 3.0})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["kcal"] == pytest.approx(89.0 * 2.4 * 3)  # 3 × 240 g
    assert body["unit"] == "1 cup, sliced"  # label preserved


async def test_portion_entry_unit_change_clears_portion_basis(auth_client):
    food, portion_id = await _food_with_portion()
    created = (
        await auth_client.post(
            "/log",
            json={
                "food_id": str(food.id),
                "date": "2026-07-24",
                "meal": "lunch",
                "quantity": 1.0,
                "unit": "serving",
                "portion_id": str(portion_id),
            },
        )
    ).json()

    resp = await auth_client.put(
        f"/log/{created['id']}", json={"quantity": 100.0, "unit": "g"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["unit"] == "g"
    assert body["kcal"] == pytest.approx(89.0)  # plain 100 g from the per-100g basis


async def test_recent_foods_carries_portion_gram_weight(auth_client):
    food, portion_id = await _food_with_portion()
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": str(food.id),
            "date": "2026-07-24",
            "meal": "snack",
            "quantity": 2.0,
            "unit": "serving",
            "portion_id": str(portion_id),
        },
    )
    assert resp.status_code in (200, 201)

    recents = (await auth_client.get("/log/recent-foods")).json()
    mine = next(r for r in recents if r["food"]["id"] == str(food.id))
    assert mine["last_quantity"] == 2.0
    assert mine["last_unit"] == "1 cup, sliced"
    assert mine["last_portion_gram_weight"] == 240.0

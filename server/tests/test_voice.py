"""Voice-logging tests (CLAUDE.md §6, §10).

Two halves, mirroring the photo suite: exhaustive coverage of the forgiving structured parser, and
the resolve→draft service driven through ``httpx.MockTransport`` (CI never reaches a real LM Studio)
with an injected food search (no DB/network). The estimate is only ever *returned* — nothing is
written to the database (CLAUDE.md §3).
"""

import json
import uuid

import httpx
import pytest

from app.models.food import Food
from app.schemas.photo import PhotoEstimateResponse
from app.services.ai.voice import parse_voice_log
from app.services.ai.voice_prompts import (
    MAX_ITEMS,
    build_voice_messages,
    parse_spoken_items,
)


def _lm_response(content: str) -> httpx.Response:
    return httpx.Response(
        200, json={"choices": [{"message": {"role": "assistant", "content": content}}]}
    )


def _mock_client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _food(name: str, *, kcal=100.0, protein=5.0, carbs=10.0, fat=2.0, serving=None) -> Food:
    return Food(
        source="user",
        name=name,
        serving_size=serving,
        kcal_per_100g=kcal,
        protein_g_per_100g=protein,
        carbs_g_per_100g=carbs,
        fat_g_per_100g=fat,
    )


# ── Parser: well-formed and messy input ──────────────────────────────────────


def test_parse_clean_array():
    items = parse_spoken_items(
        '[{"food":"eggs","quantity":2,"unit":"serving"},{"food":"banana","quantity":1,"unit":"serving"}]'
    )
    assert [i["food"] for i in items] == ["eggs", "banana"]
    assert items[0]["quantity"] == 2.0


def test_parse_strips_fences_and_preamble():
    raw = 'Sure:\n```json\n[{"food":"rice","quantity":150,"unit":"g"}]\n```'
    items = parse_spoken_items(raw)
    assert items == [{"food": "rice", "quantity": 150.0, "unit": "g"}]


def test_parse_single_object_becomes_one_item():
    items = parse_spoken_items('{"food":"apple","quantity":1,"unit":"serving"}')
    assert items[0]["food"] == "apple"


def test_parse_nested_under_key():
    items = parse_spoken_items('{"items":[{"food":"toast","quantity":2,"unit":"serving"}]}')
    assert items[0]["food"] == "toast"


def test_parse_accepts_name_alias_for_food():
    items = parse_spoken_items('[{"name":"oats","quantity":1,"unit":"serving"}]')
    assert items[0]["food"] == "oats"


def test_parse_normalizes_units():
    items = parse_spoken_items(
        '[{"food":"a","quantity":1,"unit":"grams"},{"food":"b","quantity":1,"unit":"ounces"},'
        '{"food":"c","quantity":1,"unit":"cups"},{"food":"d","quantity":1,"unit":"pieces"}]'
    )
    assert [i["unit"] for i in items] == ["g", "oz", "serving", "serving"]


def test_parse_defaults_and_floors_quantity():
    # Missing / zero / negative quantity all default to 1 (you ate *something*).
    items = parse_spoken_items(
        '[{"food":"a","unit":"serving"},{"food":"b","quantity":0,"unit":"serving"},'
        '{"food":"c","quantity":-3,"unit":"serving"}]'
    )
    assert [i["quantity"] for i in items] == [1.0, 1.0, 1.0]


def test_parse_coerces_string_quantity():
    items = parse_spoken_items('[{"food":"soup","quantity":"about 2","unit":"serving"}]')
    assert items[0]["quantity"] == 2.0


@pytest.mark.parametrize(
    "raw",
    ["", "   ", "no food here", "[]", "[1,2,3]", "{}", "not json {{{"],
)
def test_parse_unusable_returns_empty(raw):
    assert parse_spoken_items(raw) == []


def test_parse_drops_items_missing_food():
    items = parse_spoken_items('[{"quantity":1,"unit":"serving"},{"food":"pear","quantity":1}]')
    assert [i["food"] for i in items] == ["pear"]


def test_parse_caps_item_count():
    big = json.dumps(
        [{"food": f"f{n}", "quantity": 1, "unit": "serving"} for n in range(MAX_ITEMS + 5)]
    )
    assert len(parse_spoken_items(big)) == MAX_ITEMS


def test_build_voice_messages_embeds_text():
    messages = build_voice_messages("two eggs and a banana")
    assert messages[0]["role"] == "system"
    assert "two eggs and a banana" in messages[1]["content"]


# ── Service: parse → resolve → editable draft (mocked LM + injected search) ───


async def test_voice_resolves_foods_into_a_draft():
    reply = '[{"food":"eggs","quantity":2,"unit":"serving"},{"food":"banana","quantity":1,"unit":"serving"}]'

    async def fake_search(db, query):
        table = {
            "eggs": _food("Egg", kcal=140.0, protein=12.0, carbs=1.0, fat=10.0, serving=50.0),
            "banana": _food("Banana", kcal=89.0, protein=1.1, carbs=23.0, fat=0.3, serving=120.0),
        }
        hit = table.get(query.lower())
        return [hit] if hit else []

    async with _mock_client(lambda r: _lm_response(reply)) as client:
        resp = await parse_voice_log(
            "two eggs and a banana", None, uuid.uuid4(), client=client, search=fake_search
        )

    assert isinstance(resp, PhotoEstimateResponse)
    assert [i.name for i in resp.items] == ["Egg", "Banana"]
    # 2 eggs × 50 g serving = 100 g → macros == per-100g values.
    egg = resp.items[0]
    assert egg.est_grams == 100.0
    assert egg.kcal == 140.0
    assert egg.protein_g == 12.0
    # 1 banana × 120 g serving = 120 g → 89 × 1.2 = 106.8 kcal.
    assert resp.items[1].est_grams == 120.0
    assert resp.items[1].kcal == pytest.approx(106.8)
    assert resp.low_confidence is False


async def test_voice_weight_units_are_direct():
    async def fake_search(db, query):
        return [_food("Rice", kcal=130.0, serving=None)]

    async with _mock_client(
        lambda r: _lm_response('[{"food":"rice","quantity":150,"unit":"g"}]')
    ) as client:
        resp = await parse_voice_log(
            "150 grams of rice", None, uuid.uuid4(), client=client, search=fake_search
        )

    assert resp.items[0].est_grams == 150.0
    assert resp.items[0].kcal == pytest.approx(130.0 * 1.5)


async def test_voice_unresolved_food_kept_as_low_confidence_stub():
    async def fake_search(db, query):
        return []  # nothing matches

    async with _mock_client(
        lambda r: _lm_response('[{"food":"moon cheese","quantity":1,"unit":"serving"}]')
    ) as client:
        resp = await parse_voice_log(
            "some moon cheese", None, uuid.uuid4(), client=client, search=fake_search
        )

    assert resp.items[0].name == "moon cheese"
    assert resp.items[0].kcal == 0.0
    assert resp.low_confidence is True
    assert resp.note is not None


async def test_voice_empty_parse_returns_note():
    async def fake_search(db, query):
        return []

    async with _mock_client(lambda r: _lm_response("I didn't catch a food")) as client:
        resp = await parse_voice_log("um", None, uuid.uuid4(), client=client, search=fake_search)

    assert resp.items == []
    assert resp.low_confidence is True
    assert resp.note is not None


# ── Route wiring (validation short-circuits before any model call) ────────────


async def test_voice_route_requires_auth(client):
    resp = await client.post("/foods/voice", json={"text": "two eggs"})
    assert resp.status_code == 401


async def test_voice_route_rejects_blank_text(auth_client):
    resp = await auth_client.post("/foods/voice", json={"text": ""})
    assert resp.status_code == 422


async def test_voice_route_rejects_overlong_text(auth_client):
    resp = await auth_client.post("/foods/voice", json={"text": "x" * 501})
    assert resp.status_code == 422

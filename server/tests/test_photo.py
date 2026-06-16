"""Photo-logging tests (CLAUDE.md §6, §10).

The heart of Phase 6 is robust parsing of messy vision-model output, so the parser gets exhaustive
table coverage including malformed cases. The LM Studio call is exercised through
``httpx.MockTransport`` so CI never reaches a real server; the route is checked for auth + upload
validation, which short-circuit before any model call (mirroring ``test_ai``). Throughout, the
estimate is only ever *returned* — nothing is written to the database (CLAUDE.md §3).
"""
import httpx
import pytest
from fastapi import HTTPException

from app.config import settings
from app.schemas.photo import PhotoEstimateResponse
from app.services.ai.photo_prompts import (
    MAX_ITEMS,
    build_vision_messages,
    parse_estimate,
)
from app.services.ai.vision import estimate_photo

# A tiny but valid-looking JPEG header is unnecessary — the route only checks the declared
# content-type and size, and the model is mocked, so any bytes stand in for "an image".
FAKE_IMAGE = b"\xff\xd8\xff\xe0fake-jpeg-bytes"


def _lm_response(content: str) -> httpx.Response:
    return httpx.Response(
        200, json={"choices": [{"message": {"role": "assistant", "content": content}}]}
    )


def _mock_client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _one_item(**over) -> str:
    base = {
        "name": "Grilled chicken",
        "est_grams": 150,
        "kcal": 250,
        "protein_g": 46,
        "carbs_g": 0,
        "fat_g": 6,
        "confidence": 0.8,
    }
    base.update(over)
    import json

    return json.dumps([base])


# ── Parser: well-formed and lightly-messy input ──────────────────────────────


def test_parse_clean_array():
    items = parse_estimate(
        '[{"name":"Banana","est_grams":120,"kcal":105,"protein_g":1.3,'
        '"carbs_g":27,"fat_g":0.4,"confidence":0.9}]'
    )
    assert len(items) == 1
    assert items[0]["name"] == "Banana"
    assert items[0]["kcal"] == 105.0
    assert items[0]["confidence"] == 0.9


def test_parse_strips_code_fences():
    raw = '```json\n[{"name":"Rice","kcal":200,"confidence":0.7}]\n```'
    items = parse_estimate(raw)
    assert len(items) == 1
    assert items[0]["name"] == "Rice"


def test_parse_single_object_becomes_one_item():
    items = parse_estimate('{"name":"Apple","kcal":95,"confidence":0.8}')
    assert len(items) == 1
    assert items[0]["name"] == "Apple"


def test_parse_list_nested_under_key():
    items = parse_estimate('{"items":[{"name":"Egg","kcal":78,"confidence":0.6}]}')
    assert len(items) == 1
    assert items[0]["name"] == "Egg"


def test_parse_recovers_json_from_preamble_prose():
    raw = 'Sure! Here is the result:\n[{"name":"Toast","kcal":80,"confidence":0.5}] hope it helps'
    items = parse_estimate(raw)
    assert len(items) == 1
    assert items[0]["name"] == "Toast"


def test_parse_defaults_missing_macros_to_zero():
    items = parse_estimate('[{"name":"Mystery","kcal":120,"confidence":0.5}]')
    assert items[0]["protein_g"] == 0.0
    assert items[0]["carbs_g"] == 0.0
    assert items[0]["fat_g"] == 0.0
    assert items[0]["est_grams"] == 0.0


def test_parse_coerces_string_numbers():
    items = parse_estimate(
        '[{"name":"Soup","est_grams":"about 300g","kcal":"150 kcal","confidence":"0.7"}]'
    )
    assert items[0]["est_grams"] == 300.0
    assert items[0]["kcal"] == 150.0
    assert items[0]["confidence"] == 0.7


# ── Parser: malformed / adversarial input (must degrade, never raise) ─────────


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "   ",
        "I can't tell what this is.",
        "[]",
        "[1, 2, 3]",  # non-dict elements
        "{}",  # object with no list and no name
        "not json at all {{{",
    ],
)
def test_parse_unusable_input_returns_empty(raw):
    assert parse_estimate(raw) == []


def test_parse_drops_items_missing_name():
    items = parse_estimate('[{"kcal":100,"confidence":0.5},{"name":"Pear","kcal":100}]')
    assert [i["name"] for i in items] == ["Pear"]


def test_parse_drops_items_missing_kcal():
    # No calories → not a usable estimate, even with a name.
    items = parse_estimate('[{"name":"Nothing","confidence":0.5}]')
    assert items == []


def test_parse_clamps_confidence_to_unit_range():
    high = parse_estimate(_one_item(confidence=1.7))
    low = parse_estimate(_one_item(confidence=-3))
    assert high[0]["confidence"] == 1.0
    assert low[0]["confidence"] == 0.0


def test_parse_defaults_confidence_when_absent():
    items = parse_estimate('[{"name":"Pasta","kcal":300}]')
    assert 0.0 <= items[0]["confidence"] <= 1.0


def test_parse_rejects_boolean_kcal():
    # bool is an int subclass — must not sneak through as 1.0 kcal.
    assert parse_estimate('[{"name":"Weird","kcal":true,"confidence":0.5}]') == []


def test_parse_negative_macros_floored_to_zero():
    items = parse_estimate(_one_item(fat_g=-10))
    assert items[0]["fat_g"] == 0.0


def test_parse_caps_item_count():
    import json

    big = json.dumps(
        [{"name": f"Food {n}", "kcal": 10, "confidence": 0.5} for n in range(MAX_ITEMS + 5)]
    )
    assert len(parse_estimate(big)) == MAX_ITEMS


# ── Prompt assembly ──────────────────────────────────────────────────────────


def test_build_vision_messages_embeds_image_data_url():
    messages = build_vision_messages("data:image/jpeg;base64,QUJD")
    assert messages[0]["role"] == "system"
    user = messages[1]
    parts = {p["type"] for p in user["content"]}
    assert parts == {"text", "image_url"}
    image_part = next(p for p in user["content"] if p["type"] == "image_url")
    assert image_part["image_url"]["url"].startswith("data:image/jpeg;base64,")


# ── Vision service (mocked LM Studio) ─────────────────────────────────────────


async def test_estimate_photo_returns_draft():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert "chat/completions" in str(request.url)
        captured["payload"] = request.read()
        return _lm_response(_one_item())

    async with _mock_client(handler) as client:
        resp = await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)

    assert isinstance(resp, PhotoEstimateResponse)
    assert len(resp.items) == 1
    assert resp.items[0].name == "Grilled chicken"
    assert resp.low_confidence is False
    # Vision model name + the base64 image both ride in the request body.
    assert settings.lm_studio_vision_model.encode() in captured["payload"]
    assert b"data:image/jpeg;base64," in captured["payload"]


async def test_estimate_photo_flags_low_confidence():
    async with _mock_client(lambda r: _lm_response(_one_item(confidence=0.2))) as client:
        resp = await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert resp.low_confidence is True
    assert resp.note is not None


async def test_estimate_photo_empty_on_no_food():
    async with _mock_client(lambda r: _lm_response("[]")) as client:
        resp = await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert resp.items == []
    assert resp.low_confidence is True
    assert resp.note is not None


async def test_estimate_photo_empty_on_garbage_reply():
    # Model returned prose with no JSON — degrade gracefully, don't error.
    async with _mock_client(lambda r: _lm_response("I'm not sure what that is.")) as client:
        resp = await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert resp.items == []
    assert resp.low_confidence is True


@pytest.mark.parametrize(
    "handler,expected",
    [
        (lambda r: httpx.Response(500), 502),
        (lambda r: httpx.Response(200, json={"nope": True}), 502),
    ],
)
async def test_estimate_photo_maps_bad_responses(handler, expected):
    async with _mock_client(handler) as client:
        with pytest.raises(HTTPException) as exc:
            await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert exc.value.status_code == expected


async def test_estimate_photo_maps_timeout_to_504():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("too slow")

    async with _mock_client(handler) as client:
        with pytest.raises(HTTPException) as exc:
            await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert exc.value.status_code == 504


async def test_estimate_photo_maps_connect_error_to_503():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    async with _mock_client(handler) as client:
        with pytest.raises(HTTPException) as exc:
            await estimate_photo(FAKE_IMAGE, "image/jpeg", client=client)
    assert exc.value.status_code == 503


# ── Route wiring (validation short-circuits before any model call) ────────────


async def test_photo_route_requires_auth(client):
    resp = await client.post(
        "/foods/photo", files={"image": ("meal.jpg", FAKE_IMAGE, "image/jpeg")}
    )
    assert resp.status_code == 401


async def test_photo_route_rejects_non_image(auth_client):
    resp = await auth_client.post(
        "/foods/photo", files={"image": ("notes.txt", b"hello", "text/plain")}
    )
    assert resp.status_code == 415


async def test_photo_route_rejects_empty_image(auth_client):
    resp = await auth_client.post(
        "/foods/photo", files={"image": ("meal.jpg", b"", "image/jpeg")}
    )
    assert resp.status_code == 400


async def test_photo_route_rejects_oversize_image(auth_client, monkeypatch):
    monkeypatch.setattr(settings, "photo_max_bytes", 4)
    resp = await auth_client.post(
        "/foods/photo", files={"image": ("meal.jpg", FAKE_IMAGE, "image/jpeg")}
    )
    assert resp.status_code == 413

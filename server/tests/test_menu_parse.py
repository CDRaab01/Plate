"""Menu-link parsing tests (the voice-suite pattern: CI never reaches LM Studio or the network).

Three layers: the forgiving component parser (menu_prompts), the fetch hardening
(menu_fetch: HTML/PDF extraction, size cap, scheme + private-IP guards), and the resolve→draft
service driven through injected fetch/search + ``httpx.MockTransport``. The parse result is only
ever *returned* — nothing is written to the database (CLAUDE.md §3), and the CRUD endpoints stay
functional with LM Studio down (the manual builder is an equal path).
"""

import json
from pathlib import Path

import httpx
import pytest
from fastapi import HTTPException

from app.config import settings
from app.services.ai.menu_prompts import (
    MAX_COMPONENTS,
    build_menu_messages,
    parse_menu_components,
)
from app.services.menu_fetch import fetch_menu_text

FIXTURES = Path(__file__).parent / "fixtures"


def _lm_response(content: str) -> httpx.Response:
    return httpx.Response(
        200, json={"choices": [{"message": {"role": "assistant", "content": content}}]}
    )


def _mock_client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


# ── Parser: well-formed and messy replies ────────────────────────────────────


def _reply(components, name="Salsa Grille"):
    return json.dumps({"restaurant_name": name, "components": components})


def test_parse_clean_reply():
    name, comps = parse_menu_components(
        _reply(
            [
                {"category": "Rice", "name": "Cilantro Lime Rice",
                 "search_term": "cilantro lime rice", "typical_grams": 150, "official": None},
                {"category": "Protein", "name": "Chicken", "search_term": None,
                 "typical_grams": None,
                 "official": {"serving_desc": "4 oz", "serving_grams": 113,
                              "kcal": 180, "protein_g": 32, "carbs_g": 0, "fat_g": 7}},
            ]
        )
    )
    assert name == "Salsa Grille"
    assert len(comps) == 2
    assert comps[0]["search_term"] == "cilantro lime rice"
    assert comps[0]["official"] is None
    assert comps[1]["official"]["kcal"] == 180.0
    assert comps[1]["official"]["serving_grams"] == 113.0


def test_parse_tolerates_fences_and_bare_list():
    raw = '```json\n[{"category":"Rice","name":"Rice","search_term":"rice"}]\n```'
    name, comps = parse_menu_components(raw)
    assert name is None
    assert comps[0]["name"] == "Rice"
    assert comps[0]["typical_grams"] is None


def test_parse_missing_category_defaults_to_menu():
    _, comps = parse_menu_components(_reply([{"name": "Queso", "search_term": "queso"}]))
    assert comps[0]["category"] == "Menu"


def test_parse_drops_absurd_official_to_estimate_path():
    """A misread number (a price, a phone number) kills the official block, not the row."""
    _, comps = parse_menu_components(
        _reply(
            [
                {"category": "Protein", "name": "Steak", "search_term": "steak",
                 "official": {"kcal": 2604801, "protein_g": 30, "carbs_g": 0, "fat_g": 10}},
                {"category": "Protein", "name": "Negative", "search_term": "x",
                 "official": {"kcal": 100, "protein_g": -5, "carbs_g": 0, "fat_g": 0}},
            ]
        )
    )
    assert all(c["official"] is None for c in comps)


def test_parse_caps_components():
    many = [{"category": "T", "name": f"c{i}", "search_term": "x"} for i in range(60)]
    _, comps = parse_menu_components(_reply(many))
    assert len(comps) == MAX_COMPONENTS


@pytest.mark.parametrize("raw", ["", "   ", "no json", "[]", "{}", "not json {{{"])
def test_parse_unusable_returns_empty(raw):
    assert parse_menu_components(raw) == (None, [])


def test_build_messages_truncates(monkeypatch):
    monkeypatch.setattr(settings, "menu_parse_max_chars", 50)
    messages = build_menu_messages("x" * 500)
    assert messages[1]["content"].endswith("x" * 50)
    assert "x" * 51 not in messages[1]["content"]


# ── Fetch hardening ──────────────────────────────────────────────────────────


@pytest.fixture
def no_ip_guard(monkeypatch):
    """Skip DNS resolution for happy-path fetch tests (MockTransport never touches the network,
    but the SSRF guard would still resolve the fake hostname)."""
    monkeypatch.setattr(settings, "menu_fetch_block_private_ips", False)


async def test_fetch_html_strips_tags(no_ip_guard):
    def handler(request):
        return httpx.Response(
            200,
            headers={"Content-Type": "text/html"},
            content=b"<html><head><style>p{}</style><script>evil()</script></head>"
            b"<body><h1>Menu</h1><p>Rice Bowl</p></body></html>",
        )

    text = await fetch_menu_text("https://menu.example.com/", client=_mock_client(handler))
    assert "Menu" in text and "Rice Bowl" in text
    assert "evil" not in text and "p{}" not in text


async def test_fetch_pdf_extracts_text(no_ip_guard):
    pdf = (FIXTURES / "menu_sample.pdf").read_bytes()

    def handler(request):
        return httpx.Response(200, headers={"Content-Type": "application/pdf"}, content=pdf)

    text = await fetch_menu_text("https://menu.example.com/menu.pdf", client=_mock_client(handler))
    assert "Barbacoa" in text


async def test_fetch_empty_pdf_422(no_ip_guard):
    """An image-only/blank PDF has no text: 422 pointing at the manual builder."""
    from pypdf import PdfWriter
    from io import BytesIO

    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    buf = BytesIO()
    writer.write(buf)

    def handler(request):
        return httpx.Response(
            200, headers={"Content-Type": "application/pdf"}, content=buf.getvalue()
        )

    with pytest.raises(HTTPException) as exc:
        await fetch_menu_text("https://menu.example.com/menu.pdf", client=_mock_client(handler))
    assert exc.value.status_code == 422


async def test_fetch_size_cap_400(no_ip_guard, monkeypatch):
    monkeypatch.setattr(settings, "menu_fetch_max_bytes", 100)

    def handler(request):
        return httpx.Response(200, headers={"Content-Type": "text/html"}, content=b"x" * 500)

    with pytest.raises(HTTPException) as exc:
        await fetch_menu_text("https://menu.example.com/", client=_mock_client(handler))
    assert exc.value.status_code == 400


async def test_fetch_rejects_bad_scheme():
    with pytest.raises(HTTPException) as exc:
        await fetch_menu_text("ftp://menu.example.com/menu.pdf")
    assert exc.value.status_code == 400


async def test_fetch_rejects_private_host():
    """SSRF guard: hostnames resolving to private/loopback ranges are refused."""
    for url in ("http://127.0.0.1/menu", "http://localhost/menu", "http://192.168.1.10/x"):
        with pytest.raises(HTTPException) as exc:
            await fetch_menu_text(url)
        assert exc.value.status_code == 400, url


async def test_fetch_guard_reruns_on_redirect():
    """A public host redirecting to a private one is refused at the hop.

    The first hop is a public literal IP so the guard passes it without real DNS; the mocked
    302 then points at loopback, which the re-run guard must refuse."""

    def handler(request):
        return httpx.Response(302, headers={"Location": "http://127.0.0.1/internal"})

    with pytest.raises(HTTPException) as exc:
        await fetch_menu_text("http://93.184.216.34/menu", client=_mock_client(handler))
    assert exc.value.status_code == 400


async def test_fetch_http_error_400(no_ip_guard):
    def handler(request):
        return httpx.Response(404)

    with pytest.raises(HTTPException) as exc:
        await fetch_menu_text("https://menu.example.com/gone", client=_mock_client(handler))
    assert exc.value.status_code == 400


# ── Endpoint: resolve→draft service through the API ──────────────────────────


GOOD_REPLY = json.dumps(
    {
        "restaurant_name": "Salsa Grille",
        "components": [
            {"category": "Rice", "name": "Cilantro Lime Rice",
             "search_term": "cilantro lime rice", "typical_grams": 150, "official": None},
            {"category": "Protein", "name": "Chicken", "search_term": None, "typical_grams": None,
             "official": {"serving_desc": "4 oz", "serving_grams": 113.0,
                          "kcal": 180, "protein_g": 32, "carbs_g": 0, "fat_g": 7}},
            {"category": "Toppings", "name": "Mystery Sauce", "search_term": "zz-nope",
             "typical_grams": 30, "official": None},
        ],
    }
)


async def _call_parse(auth_client, monkeypatch, *, reply=None, lm_handler=None, food=None):
    """Drive POST /restaurants/parse-menu with injected fetch/search/LM (no network)."""
    import app.routers.restaurants as restaurants_router
    from app.services.ai import menu as menu_service

    async def fake_fetch(url):
        return "MENU TEXT"

    async def fake_search(db, term):
        if food is not None and "rice" in term:
            return [food]
        return []

    def default_handler(request):
        return _lm_response(reply)

    client = _mock_client(lm_handler or default_handler)

    async def patched(url, db, user_id):
        return await menu_service.parse_menu(
            url, db, user_id, client=client, fetch=fake_fetch, search=fake_search
        )

    monkeypatch.setattr(restaurants_router, "parse_menu", patched)
    return await auth_client.post("/restaurants/parse-menu", json={"url": "https://x.example/m"})


async def test_parse_menu_endpoint_official_and_estimate(auth_client, recipe_food, monkeypatch):
    resp = await _call_parse(auth_client, monkeypatch, reply=GOOD_REPLY, food=recipe_food)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["restaurant_name"] == "Salsa Grille"
    by_name = {c["name"]: c for c in body["components"]}

    rice = by_name["Cilantro Lime Rice"]
    assert rice["source"] == "estimate"
    assert rice["food_id"] == str(recipe_food.id)
    assert rice["food_name"] == recipe_food.name
    assert rice["quantity"] == 150.0 and rice["unit"] == "g"
    assert rice["kcal"] == pytest.approx(133.5)  # 150 g × 0.89
    assert rice["confidence"] == 0.7

    chicken = by_name["Chicken"]
    assert chicken["source"] == "official"
    assert chicken["macros"]["kcal"] == 180.0  # carried verbatim, ready for the macros block
    assert chicken["kcal"] == 180.0
    assert chicken["confidence"] == 0.9

    mystery = by_name["Mystery Sauce"]
    assert mystery["source"] == "estimate"
    assert mystery["food_id"] is None
    assert mystery["confidence"] == 0.2
    assert body["low_confidence"] is True
    assert body["note"]


async def test_parse_menu_endpoint_empty_reply(auth_client, monkeypatch):
    resp = await _call_parse(auth_client, monkeypatch, reply="[]")
    assert resp.status_code == 200
    body = resp.json()
    assert body["components"] == []
    assert body["low_confidence"] is True
    assert "manually" in body["note"]


async def test_parse_menu_lm_unreachable_503_but_crud_still_works(
    auth_client, recipe_food, monkeypatch
):
    def down(request):
        raise httpx.ConnectError("refused")

    resp = await _call_parse(auth_client, monkeypatch, lm_handler=down)
    assert resp.status_code == 503

    # The manual path is unaffected by LM Studio being down.
    manual = await auth_client.post(
        "/restaurants",
        json={
            "name": "Manual Build",
            "components": [
                {"category": "Rice", "name": "Rice", "food_id": str(recipe_food.id),
                 "quantity": 100.0, "unit": "g"}
            ],
        },
    )
    assert manual.status_code == 201


async def test_parse_menu_lm_timeout_504(auth_client, monkeypatch):
    def slow(request):
        raise httpx.ReadTimeout("slow")

    resp = await _call_parse(auth_client, monkeypatch, lm_handler=slow)
    assert resp.status_code == 504


async def test_parse_menu_requires_auth(client):
    assert (
        await client.post("/restaurants/parse-menu", json={"url": "https://x.example/m"})
    ).status_code == 401


async def test_parse_menu_rejects_non_http_url(auth_client):
    resp = await auth_client.post("/restaurants/parse-menu", json={"url": "notaurl"})
    assert resp.status_code == 422

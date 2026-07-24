"""Food search ranking: a user's recently-logged foods surface first (quick-log ergonomics)."""

from app.database import AsyncSessionLocal
from app.models.food import Food


async def _add_food(name: str) -> str:
    async with AsyncSessionLocal() as session:
        food = Food(
            source="user",
            name=name,
            kcal_per_100g=100.0,
            protein_g_per_100g=1.0,
            carbs_g_per_100g=10.0,
            fat_g_per_100g=1.0,
        )
        session.add(food)
        await session.commit()
        await session.refresh(food)
        return str(food.id)


async def _log(auth_client, food_id: str):
    resp = await auth_client.post(
        "/log",
        json={
            "food_id": food_id,
            "date": "2026-07-15",
            "meal": "lunch",
            "quantity": 100.0,
            "unit": "g",
        },
    )
    assert resp.status_code == 201, resp.text


async def test_search_defaults_to_alphabetical(auth_client):
    early = await _add_food("Aaa Zqxunique")
    late = await _add_food("Zzz Zqxunique")
    ids = [
        r["id"] for r in (await auth_client.get("/foods/search", params={"q": "Zqxunique"})).json()
    ]
    assert ids.index(early) < ids.index(late)  # A before Z when nothing logged


async def test_recently_logged_food_ranks_first(auth_client):
    early = await _add_food("Aaa Qwtunique")  # alphabetically first
    late = await _add_food("Zzz Qwtunique")  # alphabetically last
    await _log(auth_client, late)  # but log the last one

    ids = [
        r["id"] for r in (await auth_client.get("/foods/search", params={"q": "Qwtunique"})).json()
    ]
    assert ids.index(late) < ids.index(early)  # recently-logged jumps to the top


async def test_literal_food_outranks_a_long_description_mention(auth_client):
    """The 'black olives pizza description ranks first' bug: a branded item that only mentions the
    query in a long name must not outrank the literal food."""
    mention = await _add_food(
        "Creme fraiche sauce, pulled pork, black olives zqp and banana peppers on a gyro pizza crust"
    )
    literal = await _add_food("Black olives zqp")
    ids = [
        r["id"]
        for r in (await auth_client.get("/foods/search", params={"q": "black olives zqp"})).json()
    ]
    assert ids.index(literal) < ids.index(mention)


async def test_token_match_finds_reordered_name(auth_client):
    """'ground turkey' must find a cached 'Turkey, ground, raw' (token-AND, not a contiguous
    substring which would miss the comma-reordered name)."""
    fid = await _add_food("Turkey grquniq, ground, raw")
    ids = [
        r["id"]
        for r in (await auth_client.get("/foods/search", params={"q": "ground grquniq"})).json()
    ]
    assert fid in ids


async def test_ranking_is_per_user(auth_client, client):
    import uuid

    early = await _add_food("Aaa Rvtunique")
    late = await _add_food("Zzz Rvtunique")
    await _log(auth_client, late)  # logged by the auth_client user only

    # A different user sees the default alphabetical order (no history of their own).
    uid = uuid.uuid4().hex[:8]
    other = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"o_{uid}@plate.com", "password": "Testpass123!"},
    )
    headers = {"Authorization": f"Bearer {other.json()['access_token']}"}
    ids = [
        r["id"]
        for r in (
            await client.get("/foods/search", params={"q": "Rvtunique"}, headers=headers)
        ).json()
    ]
    assert ids.index(early) < ids.index(late)  # other user: still alphabetical

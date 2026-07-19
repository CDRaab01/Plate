"""API tests for restaurant/chain checkbox templates: CRUD, sharing, official-food minting.

Sharing model under test: restaurants default ``shared`` — another account can list/get/log them
(entries land in the logger's own diary) but never patch/replace/delete them; ``shared=false``
hides them entirely. A component's inline ``macros`` block mints an official food branded with
the restaurant's name.
"""
import uuid

DAY = "2026-07-18"


async def _register(client) -> str:
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Diner", "email": f"diner_{uid}@plate.com", "password": "Testpass123!"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


def _bowl_payload(recipe_food, *, shared=True):
    return {
        "name": "Salsa Grille",
        "menu_url": "https://salsagrille.com/menu.pdf",
        "shared": shared,
        "components": [
            {
                "category": "Rice",
                "name": "Cilantro Lime Rice",
                "food_id": str(recipe_food.id),
                "quantity": 150.0,
                "unit": "g",
                "default_checked": True,
            },
            {
                "category": "Protein",
                "name": "Barbacoa",
                "food_id": str(recipe_food.id),
                "quantity": 100.0,
                "unit": "g",
            },
        ],
    }


async def _make_restaurant(auth_client, recipe_food, **kwargs):
    resp = await auth_client.post("/restaurants", json=_bowl_payload(recipe_food, **kwargs))
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_requires_auth(client):
    assert (await client.get("/restaurants")).status_code == 401


async def test_create_computes_component_macros_and_order(auth_client, recipe_food):
    body = await _make_restaurant(auth_client, recipe_food)
    assert body["name"] == "Salsa Grille"
    assert body["is_owner"] is True
    assert body["shared"] is True
    assert [c["order"] for c in body["components"]] == [0, 1]
    rice = body["components"][0]
    assert rice["category"] == "Rice"
    assert rice["food_name"] == recipe_food.name
    assert rice["default_checked"] is True
    # 150 g of an 89 kcal/100g food at the default portion.
    assert rice["kcal"] == 133.5


async def test_default_checked_does_not_leak_to_other_accounts(auth_client, client, recipe_food):
    """Regression: `default_checked` is the owner's private "usual order" pre-tick config. On a
    shared restaurant it must NOT show up pre-checked on another account's log sheet."""
    created = await _make_restaurant(auth_client, recipe_food, shared=True)
    assert created["components"][0]["default_checked"] is True  # owner sees their own default

    other = await _register(client)
    client.headers["Authorization"] = f"Bearer {other}"

    one = await client.get(f"/restaurants/{created['id']}")
    assert one.status_code == 200
    assert one.json()["is_owner"] is False
    # The non-owner gets a clean sheet — no pre-ticks leaked from the owner.
    assert all(c["default_checked"] is False for c in one.json()["components"])

    listed = await client.get("/restaurants")
    row = next(r for r in listed.json() if r["id"] == created["id"])
    assert all(c["default_checked"] is False for c in row["components"])


async def test_list_and_get(auth_client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food)
    lst = await auth_client.get("/restaurants")
    assert lst.status_code == 200
    assert created["id"] in [r["id"] for r in lst.json()]
    one = await auth_client.get(f"/restaurants/{created['id']}")
    assert one.status_code == 200
    assert one.json()["id"] == created["id"]


async def test_patch_fields(auth_client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food)
    resp = await auth_client.patch(
        f"/restaurants/{created['id']}", json={"name": "Salsa Grille North", "shared": False}
    )
    assert resp.status_code == 200
    assert resp.json()["name"] == "Salsa Grille North"
    assert resp.json()["shared"] is False


async def test_replace_components(auth_client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food)
    resp = await auth_client.put(
        f"/restaurants/{created['id']}/components",
        json={
            "components": [
                {
                    "category": "Toppings",
                    "name": "Queso",
                    "food_id": str(recipe_food.id),
                    "quantity": 50.0,
                    "unit": "g",
                }
            ]
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["components"]) == 1
    assert body["components"][0]["name"] == "Queso"
    assert body["components"][0]["order"] == 0


async def test_delete(auth_client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food)
    assert (await auth_client.delete(f"/restaurants/{created['id']}")).status_code == 204
    assert (await auth_client.get(f"/restaurants/{created['id']}")).status_code == 404


async def test_macros_block_mints_official_food(auth_client):
    """An inline macros block creates a linked food branded with the restaurant name."""
    resp = await auth_client.post(
        "/restaurants",
        json={
            "name": "Chipotle",
            "components": [
                {
                    "category": "Protein",
                    "name": "Chicken",
                    "macros": {
                        "serving_desc": "1 scoop (4 oz)",
                        "serving_grams": 113.0,
                        "kcal": 180.0,
                        "protein_g": 32.0,
                        "carbs_g": 0.0,
                        "fat_g": 7.0,
                    },
                    "quantity": 1.0,
                    "unit": "serving",
                }
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    comp = resp.json()["components"][0]
    assert comp["food_id"] is not None
    assert comp["food_name"] == "Chicken"
    # One serving carries the official numbers verbatim.
    assert comp["kcal"] == 180.0
    assert comp["protein_g"] == 32.0
    # The minted food is a real, searchable row with the chain as brand.
    food = await auth_client.get(f"/foods/{comp['food_id']}")
    assert food.status_code == 200
    assert food.json()["brand"] == "Chipotle"


async def test_macros_without_serving_grams_logs_by_serving(auth_client):
    resp = await auth_client.post(
        "/restaurants",
        json={
            "name": "Qdoba",
            "components": [
                {
                    "category": "Extras",
                    "name": "Queso Diablo",
                    "macros": {"kcal": 120.0, "protein_g": 5.0, "carbs_g": 4.0, "fat_g": 9.0},
                }
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    comp = resp.json()["components"][0]
    assert comp["unit"] == "serving"
    assert comp["kcal"] == 120.0


async def test_component_validation_422(auth_client, recipe_food):
    base = {"category": "Rice", "name": "Rice", "food_id": str(recipe_food.id)}
    bad = [
        {**base, "category": "  "},
        {**base, "name": ""},
        {**base, "quantity": 0},
        {**base, "unit": "cup"},
        {**base, "macros": {"kcal": 1, "protein_g": 0, "carbs_g": 0, "fat_g": 0}},  # both sources
    ]
    for component in bad:
        resp = await auth_client.post(
            "/restaurants", json={"name": "Bad", "components": [component]}
        )
        assert resp.status_code == 422, component


async def test_shared_restaurant_visible_and_loggable_by_other_account(
    auth_client, client, recipe_food
):
    created = await _make_restaurant(auth_client, recipe_food, shared=True)
    other = await _register(client)
    client.headers["Authorization"] = f"Bearer {other}"

    lst = await client.get("/restaurants")
    row = next(r for r in lst.json() if r["id"] == created["id"])
    assert row["is_owner"] is False

    assert (await client.get(f"/restaurants/{created['id']}")).status_code == 200

    # Logging from a shared restaurant writes to the *caller's* diary.
    rice_id = created["components"][0]["id"]
    resp = await client.post(
        f"/restaurants/{created['id']}/log",
        json={"date": DAY, "meal": "lunch", "selections": [{"component_id": rice_id}]},
    )
    assert resp.status_code == 201, resp.text
    day = await client.get("/log", params={"date": DAY})
    by_meal = {m["meal"]: m for m in day.json()["meals"]}
    assert by_meal["lunch"]["totals"]["kcal"] == 133.5


async def test_shared_restaurant_not_editable_by_other_account(auth_client, client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food, shared=True)
    other = await _register(client)
    client.headers["Authorization"] = f"Bearer {other}"
    rid = created["id"]
    assert (await client.patch(f"/restaurants/{rid}", json={"name": "Hijack"})).status_code == 404
    assert (
        await client.put(f"/restaurants/{rid}/components", json={"components": []})
    ).status_code == 404
    assert (await client.delete(f"/restaurants/{rid}")).status_code == 404


async def test_private_restaurant_hidden_from_other_account(auth_client, client, recipe_food):
    created = await _make_restaurant(auth_client, recipe_food, shared=False)
    other = await _register(client)
    client.headers["Authorization"] = f"Bearer {other}"
    assert created["id"] not in [r["id"] for r in (await client.get("/restaurants")).json()]
    assert (await client.get(f"/restaurants/{created['id']}")).status_code == 404
    resp = await client.post(
        f"/restaurants/{created['id']}/log",
        json={
            "date": DAY,
            "meal": "lunch",
            "selections": [{"component_id": created["components"][0]["id"]}],
        },
    )
    assert resp.status_code == 404

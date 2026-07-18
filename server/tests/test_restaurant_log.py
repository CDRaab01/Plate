"""Logging a restaurant meal: ticked components → snapshotted food_log_entries.

Mirrors the recipe-log tests: scaled macro math is table-checked, snapshots are immutable
history (a later component edit doesn't touch logged entries), and selection ids can't cross
restaurant boundaries.
"""
import uuid

import pytest

from app.database import AsyncSessionLocal
from app.models.food import Food

DAY = "2026-07-18"


async def _make_bowl(auth_client, recipe_food):
    """A five-component bowl (all linked to recipe_food, 89 kcal/100g)."""
    components = [
        {"category": "Rice", "name": "Rice", "food_id": str(recipe_food.id),
         "quantity": 150.0, "unit": "g", "default_checked": True},
        {"category": "Beans", "name": "Black Beans", "food_id": str(recipe_food.id),
         "quantity": 100.0, "unit": "g"},
        {"category": "Protein", "name": "Chicken", "food_id": str(recipe_food.id),
         "quantity": 4.0, "unit": "oz"},
        {"category": "Protein", "name": "Steak", "food_id": str(recipe_food.id),
         "quantity": 1.0, "unit": "serving"},
        {"category": "Toppings", "name": "Queso", "food_id": str(recipe_food.id),
         "quantity": 30.0, "unit": "g"},
    ]
    resp = await auth_client.post(
        "/restaurants", json={"name": "Salsa Grille", "components": components}
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _by_name(restaurant):
    return {c["name"]: c for c in restaurant["components"]}


async def test_log_selected_components_scales_each(auth_client, recipe_food):
    bowl = await _make_bowl(auth_client, recipe_food)
    comps = _by_name(bowl)
    selections = [
        {"component_id": comps["Rice"]["id"]},
        {"component_id": comps["Steak"]["id"]},
        {"component_id": comps["Queso"]["id"]},
    ]
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={"date": DAY, "meal": "dinner", "selections": selections},
    )
    assert resp.status_code == 201, resp.text
    entries = resp.json()
    assert len(entries) == 3
    kcals = sorted(e["kcal"] for e in entries)
    # Rice 150 g × 0.89 = 133.5; Steak 1 serving = 118 g × 0.89 = 105.02; Queso 30 g × 0.89 = 26.7.
    assert kcals == pytest.approx(sorted([26.7, 105.02, 133.5]))

    day = await auth_client.get("/log", params={"date": DAY})
    by_meal = {m["meal"]: m for m in day.json()["meals"]}
    assert by_meal["dinner"]["totals"]["kcal"] == pytest.approx(133.5 + 105.02 + 26.7)


async def test_quantity_override(auth_client, recipe_food):
    bowl = await _make_bowl(auth_client, recipe_food)
    rice = _by_name(bowl)["Rice"]
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={
            "date": "2026-07-19",
            "meal": "lunch",
            "selections": [{"component_id": rice["id"], "quantity": 300.0}],
        },
    )
    assert resp.status_code == 201, resp.text
    entry = resp.json()[0]
    assert entry["quantity"] == 300.0
    assert entry["kcal"] == 267.0  # 300 g × 0.89


async def test_ounce_component_scales_via_grams(auth_client, recipe_food):
    bowl = await _make_bowl(auth_client, recipe_food)
    chicken = _by_name(bowl)["Chicken"]
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={
            "date": "2026-07-20",
            "meal": "lunch",
            "selections": [{"component_id": chicken["id"]}],
        },
    )
    assert resp.status_code == 201, resp.text
    # 4 oz = 113.398 g → × 0.89 kcal/g basis.
    assert abs(resp.json()[0]["kcal"] - 100.92) < 0.05


async def test_foreign_component_id_400(auth_client, recipe_food):
    bowl_a = await _make_bowl(auth_client, recipe_food)
    resp_b = await auth_client.post(
        "/restaurants",
        json={
            "name": "Other Place",
            "components": [
                {"category": "Rice", "name": "Rice", "food_id": str(recipe_food.id),
                 "quantity": 100.0, "unit": "g"}
            ],
        },
    )
    foreign_id = resp_b.json()["components"][0]["id"]
    resp = await auth_client.post(
        f"/restaurants/{bowl_a['id']}/log",
        json={"date": DAY, "meal": "lunch", "selections": [{"component_id": foreign_id}]},
    )
    assert resp.status_code == 400


async def test_random_component_id_400_and_empty_selections_422(auth_client, recipe_food):
    bowl = await _make_bowl(auth_client, recipe_food)
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={"date": DAY, "meal": "lunch", "selections": [{"component_id": str(uuid.uuid4())}]},
    )
    assert resp.status_code == 400
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log", json={"date": DAY, "meal": "lunch", "selections": []}
    )
    assert resp.status_code == 422


async def test_unlinked_component_skipped_and_all_unlinked_400(auth_client, recipe_food):
    """Deleting a component's food orphans it (SET NULL): it's skipped when ticked alongside a
    linked one, and a selection of only orphans is a 400."""
    resp = await auth_client.post(
        "/restaurants",
        json={
            "name": "Orphanage",
            "components": [
                {"category": "Rice", "name": "Doomed", "food_id": str(recipe_food.id),
                 "quantity": 100.0, "unit": "g"},
                {"category": "Rice", "name": "Unlinked", "quantity": 100.0, "unit": "g"},
            ],
        },
    )
    bowl = resp.json()
    unlinked = _by_name(bowl)["Unlinked"]
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={"date": DAY, "meal": "snack", "selections": [{"component_id": unlinked["id"]}]},
    )
    assert resp.status_code == 400

    doomed = _by_name(bowl)["Doomed"]
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={
            "date": "2026-07-21",
            "meal": "snack",
            "selections": [{"component_id": doomed["id"]}, {"component_id": unlinked["id"]}],
        },
    )
    assert resp.status_code == 201, resp.text
    assert len(resp.json()) == 1  # the unlinked row was skipped, not fatal


async def test_logged_entries_are_immutable_history(auth_client, recipe_food):
    bowl = await _make_bowl(auth_client, recipe_food)
    rice = _by_name(bowl)["Rice"]
    day = "2026-07-22"
    resp = await auth_client.post(
        f"/restaurants/{bowl['id']}/log",
        json={"date": day, "meal": "dinner", "selections": [{"component_id": rice["id"]}]},
    )
    assert resp.status_code == 201

    # Replace the components entirely; the logged entry must keep its snapshot.
    await auth_client.put(f"/restaurants/{bowl['id']}/components", json={"components": []})
    day_resp = await auth_client.get("/log", params={"date": day})
    by_meal = {m["meal"]: m for m in day_resp.json()["meals"]}
    assert by_meal["dinner"]["totals"]["kcal"] == 133.5


async def test_minted_food_deletion_orphans_component(auth_client):
    """Deleting a minted official food SET-NULLs the component instead of cascading it away."""
    resp = await auth_client.post(
        "/restaurants",
        json={
            "name": "Chipotle",
            "components": [
                {"category": "Protein", "name": "Chicken",
                 "macros": {"kcal": 180.0, "protein_g": 32.0, "carbs_g": 0.0, "fat_g": 7.0}}
            ],
        },
    )
    bowl = resp.json()
    food_id = bowl["components"][0]["food_id"]
    async with AsyncSessionLocal() as session:
        food = await session.get(Food, uuid.UUID(food_id))
        await session.delete(food)
        await session.commit()
    one = await auth_client.get(f"/restaurants/{bowl['id']}")
    comp = one.json()["components"][0]
    assert comp["food_id"] is None
    assert comp["name"] == "Chicken"  # the label survives
    assert comp["kcal"] is None

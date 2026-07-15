"""Quick-log ergonomics: recent-foods surface + copy-day (CLAUDE.md §6)."""

DAY = "2026-07-14"
NEXT = "2026-07-15"


async def _log(auth_client, food_id, day, meal, qty=100.0, unit="g"):
    resp = await auth_client.post(
        "/log",
        json={"food_id": str(food_id), "date": day, "meal": meal, "quantity": qty, "unit": unit},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ── recent foods ─────────────────────────────────────────────────────────────


async def test_recent_foods_requires_auth(client):
    assert (await client.get("/log/recent-foods")).status_code == 401


async def test_recent_foods_empty_for_new_user(auth_client):
    resp = await auth_client.get("/log/recent-foods")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_recent_foods_carries_last_portion(auth_client, food):
    await _log(auth_client, food.id, DAY, "lunch", qty=150.0, unit="g")
    body = (await auth_client.get("/log/recent-foods")).json()
    assert len(body) == 1
    assert body[0]["food"]["id"] == str(food.id)
    assert body[0]["last_meal"] == "lunch"
    assert body[0]["last_quantity"] == 150.0
    assert body[0]["last_unit"] == "g"


async def test_recent_foods_dedupes_keeping_most_recent(auth_client, food):
    await _log(auth_client, food.id, DAY, "breakfast", qty=100.0)
    await _log(auth_client, food.id, NEXT, "dinner", qty=250.0)
    body = (await auth_client.get("/log/recent-foods")).json()
    assert len(body) == 1  # one distinct food, not one row per log
    assert body[0]["last_meal"] == "dinner"  # the most-recently-logged portion wins
    assert body[0]["last_quantity"] == 250.0


async def test_recent_foods_excludes_quick_adds(auth_client):
    # A quick-add has no source food, so there's nothing to re-log by id — it must not appear.
    resp = await auth_client.post(
        "/log/quick-add",
        json={
            "date": DAY,
            "meal": "snack",
            "name": "Mystery snack",
            "kcal": 200.0,
            "protein_g": 5.0,
            "carbs_g": 20.0,
            "fat_g": 8.0,
        },
    )
    assert resp.status_code == 201, resp.text
    assert (await auth_client.get("/log/recent-foods")).json() == []


# ── copy day ─────────────────────────────────────────────────────────────────


async def test_copy_day_duplicates_all_entries(auth_client, food):
    await _log(auth_client, food.id, DAY, "breakfast", qty=100.0)
    await _log(auth_client, food.id, DAY, "dinner", qty=200.0)

    resp = await auth_client.post("/log/copy-day", json={"from_date": DAY, "to_date": NEXT})
    assert resp.status_code == 201, resp.text
    assert len(resp.json()) == 2

    day = (await auth_client.get("/log", params={"date": NEXT})).json()
    meals = {m["meal"]: m["entries"] for m in day["meals"]}
    assert len(meals["breakfast"]) == 1
    assert len(meals["dinner"]) == 1
    assert meals["breakfast"][0]["quantity"] == 100.0


async def test_copy_day_is_additive(auth_client, food):
    await _log(auth_client, food.id, DAY, "lunch", qty=100.0)
    await _log(auth_client, food.id, NEXT, "lunch", qty=50.0)  # a pre-existing entry on the target

    await auth_client.post("/log/copy-day", json={"from_date": DAY, "to_date": NEXT})

    day = (await auth_client.get("/log", params={"date": NEXT})).json()
    lunch = next(m for m in day["meals"] if m["meal"] == "lunch")["entries"]
    assert len(lunch) == 2  # original + copied, not overwritten


async def test_copy_empty_day_is_noop(auth_client):
    resp = await auth_client.post(
        "/log/copy-day", json={"from_date": "2020-01-01", "to_date": NEXT}
    )
    assert resp.status_code == 201
    assert resp.json() == []

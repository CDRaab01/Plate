"""Goals + computed-targets API tests (CLAUDE.md §7, §10).

Exercises setting/reading a goal, target computation (including that the daily-log targets change
after a goal is set), and the 404 path for users with no goal. All math corner cases live in
``test_targets.py``; here we just confirm the API surface and that the engine wires up correctly.
"""

import pytest

MALE_MODERATE = {
    "goal_type": "maintain",
    "weight_kg": 80.0,
    "height_cm": 180.0,
    "age": 30,
    "sex": "male",
    "activity_level": "moderate",
    "rate_kg_per_week": 0.0,
}


# ── PUT /goals ────────────────────────────────────────────────────────────────


async def test_set_goal_returns_goal(auth_client):
    resp = await auth_client.put("/goals", json=MALE_MODERATE)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["goal_type"] == "maintain"
    assert body["weight_kg"] == 80.0
    assert "id" in body
    assert "created_at" in body


async def test_set_goal_requires_auth(client):
    resp = await client.put("/goals", json=MALE_MODERATE)
    assert resp.status_code == 401


@pytest.mark.parametrize(
    "field,bad_value",
    [
        ("goal_type", "shrink"),
        ("sex", "other"),
        ("activity_level", "olympic"),
        ("weight_kg", -1.0),
        ("height_cm", 0.0),
        ("age", 0),
        ("age", 200),
    ],
)
async def test_set_goal_validates_inputs(auth_client, field, bad_value):
    body = {**MALE_MODERATE, field: bad_value}
    resp = await auth_client.put("/goals", json=body)
    assert resp.status_code == 422


async def test_setting_goal_twice_replaces_active(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    cut_goal = {**MALE_MODERATE, "goal_type": "cut", "rate_kg_per_week": -0.5}
    await auth_client.put("/goals", json=cut_goal)

    resp = await auth_client.get("/goals")
    assert resp.status_code == 200
    assert resp.json()["goal_type"] == "cut"
    assert resp.json()["rate_kg_per_week"] == -0.5


# ── GET /goals ────────────────────────────────────────────────────────────────


async def test_get_goal_404_before_set(auth_client):
    resp = await auth_client.get("/goals")
    assert resp.status_code == 404


async def test_get_goal_returns_most_recent(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    resp = await auth_client.get("/goals")
    assert resp.status_code == 200
    assert resp.json()["activity_level"] == "moderate"


# ── GET /goals/targets ────────────────────────────────────────────────────────


async def test_targets_404_without_goal(auth_client):
    resp = await auth_client.get("/goals/targets")
    assert resp.status_code == 404


async def test_targets_computed_from_goal(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    resp = await auth_client.get("/goals/targets")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # BMR 1780 × moderate 1.55 = 2759; maintain rate 0 → 2759 kcal.
    assert body["kcal"] == pytest.approx(2759.0, abs=1.0)
    assert body["protein_g"] == pytest.approx(128.0, abs=1.0)  # 80 kg × 1.6
    assert body["fat_g"] > 0
    assert body["carbs_g"] > 0
    assert "date" in body


async def test_targets_change_after_goal_update(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    maintain_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    cut_goal = {**MALE_MODERATE, "goal_type": "cut", "rate_kg_per_week": -0.5}
    await auth_client.put("/goals", json=cut_goal)
    cut_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    assert cut_kcal == pytest.approx(maintain_kcal - 550.0, abs=1.0)


async def test_targets_accepts_explicit_date(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    resp = await auth_client.get("/goals/targets", params={"date": "2026-01-01"})
    assert resp.status_code == 200
    assert resp.json()["date"] == "2026-01-01"


# ── Daily-log targets reflect the user's goal ─────────────────────────────────


async def test_log_targets_use_static_when_no_goal(auth_client):
    resp = await auth_client.get("/log", params={"date": "2026-09-01"})
    assert resp.status_code == 200
    # No goal set → static 2000 kcal placeholder.
    assert resp.json()["targets"]["kcal"] == 2000.0


async def test_log_targets_use_computed_when_goal_set(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    resp = await auth_client.get("/log", params={"date": "2026-09-01"})
    assert resp.status_code == 200
    # Real targets (~2759 kcal) replace the static placeholder.
    assert resp.json()["targets"]["kcal"] == pytest.approx(2759.0, abs=1.0)


# ── Adaptive targets: latest weigh-in feeds the engine ────────────────────────


async def test_targets_use_goal_weight_without_weigh_in(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)  # 80 kg
    resp = await auth_client.get("/goals/targets")
    # No weigh-in → goal's 80 kg → ~2759 kcal (same as the static-goal case).
    assert resp.json()["kcal"] == pytest.approx(2759.0, abs=1.0)


async def test_targets_prefer_latest_weigh_in_over_goal_weight(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)  # goal stored at 80 kg
    base_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    # A heavier weigh-in (90 kg) on/before the target day should raise the targets.
    await auth_client.post(
        "/metrics/weight", json={"date": "2026-09-01", "weight": 90.0, "unit": "kg"}
    )
    heavier = (await auth_client.get("/goals/targets", params={"date": "2026-09-02"})).json()[
        "kcal"
    ]
    assert heavier > base_kcal


async def test_targets_weigh_in_after_day_is_ignored(auth_client):
    await auth_client.put("/goals", json=MALE_MODERATE)
    # Weigh-in dated AFTER the queried day must not apply (uses weight in effect then).
    await auth_client.post(
        "/metrics/weight", json={"date": "2026-09-10", "weight": 90.0, "unit": "kg"}
    )
    earlier = (await auth_client.get("/goals/targets", params={"date": "2026-09-01"})).json()[
        "kcal"
    ]
    assert earlier == pytest.approx(2759.0, abs=1.0)  # falls back to goal's 80 kg


# ── Goal isolation (one user's goal doesn't bleed to another) ────────────────


async def test_goals_are_user_scoped(auth_client, client):
    await auth_client.put("/goals", json=MALE_MODERATE)

    import uuid

    uid = uuid.uuid4().hex[:8]
    r = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"other_{uid}@plate.com", "password": "Testpass123!"},
    )
    other_token = r.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {other_token}"

    resp = await client.get("/goals")
    assert resp.status_code == 404


# ── goal_type is authoritative for the rate sign (cut = deficit, bulk = surplus) ──


def test_goalupsert_normalizes_rate_sign_from_goal_type():
    from app.schemas.goal import GoalUpsert

    base = dict(weight_kg=80.0, height_cm=180.0, age=30, sex="male", activity_level="moderate")
    # A "cut" is always a deficit even if the client sent a positive (or unsigned) rate — the bug.
    assert GoalUpsert(goal_type="cut", rate_kg_per_week=0.5, **base).rate_kg_per_week == -0.5
    assert GoalUpsert(goal_type="cut", rate_kg_per_week=-0.5, **base).rate_kg_per_week == -0.5
    # A "bulk" is always a surplus.
    assert GoalUpsert(goal_type="bulk", rate_kg_per_week=-0.5, **base).rate_kg_per_week == 0.5
    assert GoalUpsert(goal_type="bulk", rate_kg_per_week=0.5, **base).rate_kg_per_week == 0.5
    # "maintain" forces 0 regardless.
    assert GoalUpsert(goal_type="maintain", rate_kg_per_week=-0.7, **base).rate_kg_per_week == 0.0


async def test_cut_with_positive_rate_still_gives_a_deficit(auth_client):
    # The reported bug: pick "Cut", enter a natural positive rate → it added a surplus (too-high kcal).
    await auth_client.put(
        "/goals", json={**MALE_MODERATE, "goal_type": "cut", "rate_kg_per_week": 0.5}
    )
    assert (await auth_client.get("/goals")).json()["rate_kg_per_week"] == -0.5
    cut_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    await auth_client.put("/goals", json={**MALE_MODERATE, "goal_type": "maintain"})
    maintain_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    assert cut_kcal < maintain_kcal  # a cut MUST land below maintenance


async def test_bulk_with_negative_rate_still_gives_a_surplus(auth_client):
    await auth_client.put(
        "/goals", json={**MALE_MODERATE, "goal_type": "bulk", "rate_kg_per_week": -0.5}
    )
    assert (await auth_client.get("/goals")).json()["rate_kg_per_week"] == 0.5
    bulk_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    await auth_client.put("/goals", json={**MALE_MODERATE, "goal_type": "maintain"})
    maintain_kcal = (await auth_client.get("/goals/targets")).json()["kcal"]

    assert bulk_kcal > maintain_kcal  # a bulk MUST land above maintenance

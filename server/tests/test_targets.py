"""Exhaustive, table-driven tests for the targets engine (CLAUDE.md §7, §10).

These pure functions are the single source of truth for a user's daily kcal/macro targets, so they
get full coverage: every Mifflin-St Jeor branch, each activity factor, the goal adjustment + safety
floor, and the macro split including the carbs-clamp edge.
"""
import pytest

from app.nutrition import constants as c
from app.nutrition.targets import (
    BodyProfile,
    Targets,
    activity_factor,
    apply_training_day_bump,
    compute_targets,
    from_goal,
    goal_adjusted_kcal,
    macro_split,
    mifflin_st_jeor_bmr,
    tdee,
)


# ── Mifflin-St Jeor BMR ───────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "weight,height,age,sex,expected",
    [
        # 10·w + 6.25·h − 5·age + sex_constant
        (80.0, 180.0, 30, "male", 1780.0),  # 800 + 1125 − 150 + 5
        (60.0, 165.0, 25, "female", 1345.25),  # 600 + 1031.25 − 125 − 161
        (80.0, 180.0, 30, "MALE", 1780.0),  # case-insensitive
        (80.0, 180.0, 30, " female ", 1614.0),  # whitespace tolerated: 800+1125-150-161
    ],
)
def test_mifflin_st_jeor_table(weight, height, age, sex, expected):
    assert mifflin_st_jeor_bmr(weight, height, age, sex) == pytest.approx(expected)


def test_bmr_rejects_unknown_sex():
    with pytest.raises(ValueError):
        mifflin_st_jeor_bmr(80.0, 180.0, 30, "other")


@pytest.mark.parametrize("weight,height,age", [(0.0, 180.0, 30), (80.0, 0.0, 30), (80.0, 180.0, 0)])
def test_bmr_rejects_non_positive_measurements(weight, height, age):
    with pytest.raises(ValueError):
        mifflin_st_jeor_bmr(weight, height, age, "male")


# ── Activity factor & TDEE ────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "level,factor",
    [
        ("sedentary", 1.2),
        ("light", 1.375),
        ("moderate", 1.55),
        ("active", 1.725),
        ("very_active", 1.9),
        ("MODERATE", 1.55),
    ],
)
def test_activity_factor_table(level, factor):
    assert activity_factor(level) == pytest.approx(factor)


def test_activity_factor_rejects_unknown():
    with pytest.raises(ValueError):
        activity_factor("olympian")


def test_tdee_is_bmr_times_factor():
    # BMR 1780 × moderate 1.55 = 2759.
    assert tdee(80.0, 180.0, 30, "male", "moderate") == pytest.approx(2759.0)


# ── Goal adjustment ───────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "maintenance,rate,expected",
    [
        (2759.0, 0.0, 2759.0),  # maintain — no change
        (2759.0, -0.5, 2209.0),  # cut: −0.5 kg/wk → −550 kcal/day
        (2759.0, 0.5, 3309.0),  # bulk: +0.5 kg/wk → +550 kcal/day
        (2759.0, -1.0, 1659.0),  # steeper cut: −1100 kcal/day, still above the floor
    ],
)
def test_goal_adjusted_kcal_table(maintenance, rate, expected):
    assert goal_adjusted_kcal(maintenance, rate) == pytest.approx(expected)


def test_goal_adjustment_never_below_floor():
    # A tiny maintenance with an aggressive cut would go far below 1200; it clamps.
    assert goal_adjusted_kcal(1456.8, -1.0) == pytest.approx(c.MIN_DAILY_KCAL)


# ── Macro split ───────────────────────────────────────────────────────────────


def test_macro_split_protein_from_bodyweight_and_carbs_fill_remainder():
    # 2209 kcal, 80 kg, cut → protein 2.0 g/kg, fat 30% of kcal (above its floor), carbs remainder.
    protein, carbs, fat = macro_split(2209.0, 80.0, "cut")
    assert protein == pytest.approx(160.0)  # 80 × 2.0
    assert fat == pytest.approx(2209.0 * 0.30 / 9)  # ≈ 73.6 g, beats the 64 g floor
    expected_carbs = (2209.0 - 160.0 * 4 - fat * 9) / 4
    assert carbs == pytest.approx(expected_carbs)


@pytest.mark.parametrize(
    "goal,protein_per_kg",
    [("maintain", 1.6), ("cut", 2.0), ("bulk", 1.8), ("unknown", 1.6)],
)
def test_macro_split_protein_per_goal(goal, protein_per_kg):
    protein, _, _ = macro_split(2200.0, 70.0, goal)
    assert protein == pytest.approx(70.0 * protein_per_kg)


def test_macro_split_fat_floor_applies_at_low_kcal():
    # At 1200 kcal for an 80 kg person, 30% of kcal (40 g) is below the 0.8 g/kg floor (64 g).
    _, _, fat = macro_split(1200.0, 80.0, "cut")
    assert fat == pytest.approx(80.0 * c.FAT_FLOOR_G_PER_KG)


def test_macro_split_clamps_carbs_to_zero_when_protein_and_fat_exceed_kcal():
    # Heavy lifter on the kcal floor: protein (260 g) + fat floor (104 g) already overshoot 1200.
    protein, carbs, fat = macro_split(1200.0, 130.0, "cut")
    assert protein == pytest.approx(260.0)
    assert fat == pytest.approx(104.0)
    assert carbs == 0.0


def test_macro_split_kcal_reconcile_when_carbs_positive():
    # When carbs aren't clamped, the macro kcal add back up to the target.
    kcal = 2500.0
    protein, carbs, fat = macro_split(kcal, 75.0, "maintain")
    assert protein * 4 + carbs * 4 + fat * 9 == pytest.approx(kcal)


# ── Full pipeline ─────────────────────────────────────────────────────────────


def test_compute_targets_end_to_end_cut():
    profile = BodyProfile(
        weight_kg=80.0,
        height_cm=180.0,
        age=30,
        sex="male",
        activity_level="moderate",
        goal_type="cut",
        rate_kg_per_week=-0.5,
    )
    targets = compute_targets(profile)
    assert isinstance(targets, Targets)
    assert targets.kcal == pytest.approx(2209.0)
    assert targets.protein_g == pytest.approx(160.0)
    assert targets.fat_g == pytest.approx(2209.0 * 0.30 / 9)
    assert targets.carbs_g > 0


def test_compute_targets_maintain_keeps_tdee():
    profile = BodyProfile(
        weight_kg=80.0,
        height_cm=180.0,
        age=30,
        sex="male",
        activity_level="moderate",
        goal_type="maintain",
        rate_kg_per_week=0.0,
    )
    assert compute_targets(profile).kcal == pytest.approx(2759.0)


# ── Training-day bump (Spotter-awareness, §7) ─────────────────────────────────


def test_apply_training_day_bump_adds_carbs_and_protein_holds_fat():
    base = Targets(kcal=2000.0, protein_g=150.0, carbs_g=200.0, fat_g=67.0)
    bumped = apply_training_day_bump(base)
    assert bumped.protein_g == pytest.approx(150.0 + c.TRAINING_DAY_PROTEIN_BUMP_G)
    assert bumped.carbs_g == pytest.approx(200.0 + c.TRAINING_DAY_CARBS_BUMP_G)
    assert bumped.fat_g == pytest.approx(67.0 + c.TRAINING_DAY_FAT_BUMP_G)


def test_apply_training_day_bump_kcal_matches_added_macros():
    base = Targets(kcal=2000.0, protein_g=150.0, carbs_g=200.0, fat_g=67.0)
    bumped = apply_training_day_bump(base)
    expected_added = (
        c.TRAINING_DAY_PROTEIN_BUMP_G * c.KCAL_PER_G_PROTEIN
        + c.TRAINING_DAY_CARBS_BUMP_G * c.KCAL_PER_G_CARB
        + c.TRAINING_DAY_FAT_BUMP_G * c.KCAL_PER_G_FAT
    )
    assert bumped.kcal == pytest.approx(2000.0 + expected_added)


def test_compute_targets_trained_raises_kcal_over_untrained():
    profile = BodyProfile(
        weight_kg=80.0,
        height_cm=180.0,
        age=30,
        sex="male",
        activity_level="moderate",
        goal_type="cut",
        rate_kg_per_week=-0.5,
    )
    untrained = compute_targets(profile)
    trained = compute_targets(profile, trained=True)
    assert trained.kcal > untrained.kcal
    assert trained.protein_g > untrained.protein_g
    assert trained.carbs_g > untrained.carbs_g
    assert trained.fat_g == pytest.approx(untrained.fat_g)  # fat held on a training day


def test_compute_targets_untrained_is_unchanged_default():
    profile = BodyProfile(
        weight_kg=80.0,
        height_cm=180.0,
        age=30,
        sex="male",
        activity_level="moderate",
        goal_type="maintain",
        rate_kg_per_week=0.0,
    )
    # Default (no training) keeps the plain TDEE-based number — the Phase 3 behaviour is preserved.
    assert compute_targets(profile).kcal == pytest.approx(2759.0)


def test_from_goal_adapts_a_goal_row():
    class FakeGoal:
        weight_kg = 70.0
        height_cm = 175.0
        age = 28
        sex = "female"
        activity_level = "active"
        goal_type = "bulk"
        rate_kg_per_week = 0.25

    profile = from_goal(FakeGoal())
    assert profile == BodyProfile(70.0, 175.0, 28, "female", "active", "bulk", 0.25)

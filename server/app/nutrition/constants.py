"""Nutrition constants — the single home for the targets-engine numbers (CLAUDE.md §7, §11).

No magic numbers inline anywhere in :mod:`app.nutrition`; every coefficient the BMR/TDEE, goal
adjustment, and macro split depend on lives here so the policy is reviewable in one place.
"""
from app.nutrition.totals import Totals

# A neutral ~2000 kcal day with a balanced macro split. Used as the daily-log target only for users
# who haven't set a goal yet; once a goal exists the engine computes real targets (CLAUDE.md §7).
STATIC_DAILY_TARGET = Totals(
    kcal=2000.0,
    protein_g=150.0,
    carbs_g=200.0,
    fat_g=67.0,
)

# ── Mifflin-St Jeor BMR ───────────────────────────────────────────────────────
# BMR = 10·weight_kg + 6.25·height_cm − 5·age + sex_constant.
MSJ_WEIGHT_COEFF = 10.0
MSJ_HEIGHT_COEFF = 6.25
MSJ_AGE_COEFF = 5.0
MSJ_SEX_CONSTANT = {"male": 5.0, "female": -161.0}

# ── TDEE ─────────────────────────────────────────────────────────────────────
# Maintenance = BMR × activity factor, keyed by the user's self-reported activity level.
ACTIVITY_FACTORS = {
    "sedentary": 1.2,
    "light": 1.375,
    "moderate": 1.55,
    "active": 1.725,
    "very_active": 1.9,
}

# ── Goal adjustment ───────────────────────────────────────────────────────────
# Energy stored in one kg of body mass; a 0.5 kg/week cut ≈ a 550 kcal/day deficit.
KCAL_PER_KG = 7700.0
DAYS_PER_WEEK = 7.0
# Hard floor so an aggressive cut rate never recommends a dangerously low intake.
MIN_DAILY_KCAL = 1200.0

# ── Macro split ───────────────────────────────────────────────────────────────
# Atwater factors (kcal per gram).
KCAL_PER_G_PROTEIN = 4.0
KCAL_PER_G_CARB = 4.0
KCAL_PER_G_FAT = 9.0

# Protein target (g per kg bodyweight) by goal — higher in a cut to spare lean mass (CLAUDE.md §7).
PROTEIN_G_PER_KG = {
    "maintain": 1.6,
    "cut": 2.0,
    "bulk": 1.8,
}
DEFAULT_PROTEIN_G_PER_KG = 1.6

# Fat takes this share of total kcal, but never drops below the floor (g per kg) needed for
# hormonal health; carbs then fill whatever kcal remain.
FAT_FRACTION_OF_KCAL = 0.30
FAT_FLOOR_G_PER_KG = 0.8

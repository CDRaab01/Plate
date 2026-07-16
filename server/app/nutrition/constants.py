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

# ── Weight trend / on-pace feedback ───────────────────────────────────────────
# The recent-rate window: the observed rate of weight change is the least-squares slope over the
# weigh-ins within this many days of the latest one, so a stale older trend doesn't drag it.
TREND_WINDOW_DAYS = 14
# Chart smoothing: each plotted point is a trailing simple moving average over up to this many
# weigh-ins, to damp day-to-day water-weight noise.
TREND_SMOOTHING_POINTS = 7
# How far the observed weekly rate may sit from the goal rate and still count as "on pace".
ON_PACE_TOLERANCE_KG_PER_WEEK = 0.15

# ── Adaptive TDEE correction (CLAUDE.md §7; ROADMAP2 T3 #1) ────────────────────
# Back-solve the user's *real* maintenance from the energy balance between what they logged and how
# their smoothed weight actually moved, then blend it into the formula TDEE so targets self-correct.
# Trailing window the intake average + observed weight rate are measured over.
ADAPTIVE_WINDOW_DAYS = 14
# A day counts as "logged" only if its intake clears this floor — a half-empty day would understate
# intake and bias the maintenance estimate downward.
MIN_LOGGED_KCAL = 800.0
# Need at least this many logged days in the window before any correction is trusted.
MIN_LOGGED_DAYS = 10
# Weigh-ins must span at least this many days (first→last) for the least-squares rate to be stable.
MIN_WEIGH_IN_SPAN_DAYS = 10
# Ceiling on how much the observed signal can pull the estimate: the formula always anchors, so a
# noisy two-week window can never fully own the number (blend weight = coverage × this).
MAX_BLEND = 0.7
# Corrected maintenance is clamped to formula × [1 − dev, 1 + dev] so a water-weight whoosh (a big
# transient weight swing) can't tank or balloon targets.
MAX_TDEE_DEVIATION = 0.30

# ── Weekly cross-app summary (suite digest) ───────────────────────────────────
# A logged day counts as calorie-adherent when its total kcal lands within this fraction of the
# day's target (a ±10% band). Protein adherence is a plain "≥ target" check, so it needs no band.
CALORIE_ADHERENCE_BAND = 0.10
# Guardrail on the summary window: the digest asks for aggregates over a bounded range only (mirrors
# Magpie's summary cap).
MAX_SUMMARY_WINDOW_DAYS = 92

# ── Training-day bump (Spotter-awareness, §7) ─────────────────────────────────
# On a day the user trained (reported by Spotter), add fuel to refuel/recover. The bump is
# expressed as gram additions skewed to carbs + protein — fat is left at its hormonal floor — and
# the kcal increase is *derived* from those grams (4·protein + 4·carbs) so the macro and calorie
# figures stay internally consistent. A single moderate session ≈ +220 kcal here.
TRAINING_DAY_CARBS_BUMP_G = 40.0
TRAINING_DAY_PROTEIN_BUMP_G = 15.0
TRAINING_DAY_FAT_BUMP_G = 0.0

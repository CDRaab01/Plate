"""Per-user calorie & macro targets (CLAUDE.md §4, §7, §11).

The single source of truth for a day's targets — clients only ever display these, never recompute.
Pure functions, exhaustively unit-tested with table cases. Phase 3 covers:

* **BMR** via Mifflin-St Jeor,
* **TDEE** = BMR × activity factor,
* **goal adjustment** from the user's target rate of weight change (cut = deficit, bulk = surplus),
* the **protein-first / fat-floor / carbs-remainder** macro split.

The training-day bump (Spotter-awareness) layers on in Phase 7; :func:`compute_targets` already takes
the inputs that bump will read, but applies no adjustment for it yet.
"""
from dataclasses import dataclass

from app.nutrition import constants as c


@dataclass(frozen=True)
class BodyProfile:
    """The inputs the targets engine reads off a user's goal.

    Duck-typed onto :class:`~app.models.user_goal.UserGoal`, so a goal row can be passed straight
    through (see :func:`from_goal`).
    """

    weight_kg: float
    height_cm: float
    age: int
    sex: str
    activity_level: str
    goal_type: str
    rate_kg_per_week: float


@dataclass(frozen=True)
class Targets:
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float


def from_goal(goal) -> BodyProfile:
    """Adapt a persisted goal row (or any object with the same fields) into a :class:`BodyProfile`."""
    return BodyProfile(
        weight_kg=goal.weight_kg,
        height_cm=goal.height_cm,
        age=goal.age,
        sex=goal.sex,
        activity_level=goal.activity_level,
        goal_type=goal.goal_type,
        rate_kg_per_week=goal.rate_kg_per_week,
    )


def mifflin_st_jeor_bmr(weight_kg: float, height_cm: float, age: int, sex: str) -> float:
    """Resting metabolic rate (kcal/day) via Mifflin-St Jeor.

    Raises :class:`ValueError` for an unsupported ``sex`` or a non-positive body measurement.
    """
    sex_key = sex.strip().lower()
    if sex_key not in c.MSJ_SEX_CONSTANT:
        raise ValueError(f"unsupported sex: {sex!r}")
    if weight_kg <= 0 or height_cm <= 0 or age <= 0:
        raise ValueError("weight, height, and age must be positive")
    return (
        c.MSJ_WEIGHT_COEFF * weight_kg
        + c.MSJ_HEIGHT_COEFF * height_cm
        - c.MSJ_AGE_COEFF * age
        + c.MSJ_SEX_CONSTANT[sex_key]
    )


def activity_factor(activity_level: str) -> float:
    """The TDEE multiplier for an activity level. Raises :class:`ValueError` if unknown."""
    key = activity_level.strip().lower()
    try:
        return c.ACTIVITY_FACTORS[key]
    except KeyError:
        raise ValueError(f"unsupported activity level: {activity_level!r}") from None


def tdee(weight_kg: float, height_cm: float, age: int, sex: str, activity_level: str) -> float:
    """Maintenance energy (kcal/day) = BMR × activity factor."""
    return mifflin_st_jeor_bmr(weight_kg, height_cm, age, sex) * activity_factor(activity_level)


def goal_adjusted_kcal(maintenance_kcal: float, rate_kg_per_week: float) -> float:
    """Apply the weight-change rate to maintenance, then clamp to the safety floor.

    A negative rate (cut) subtracts a deficit; a positive rate (bulk) adds a surplus. The result is
    never below :data:`~app.nutrition.constants.MIN_DAILY_KCAL`.
    """
    daily_delta = rate_kg_per_week * c.KCAL_PER_KG / c.DAYS_PER_WEEK
    return max(maintenance_kcal + daily_delta, c.MIN_DAILY_KCAL)


def macro_split(kcal: float, weight_kg: float, goal_type: str) -> tuple[float, float, float]:
    """Split a kcal target into ``(protein_g, carbs_g, fat_g)``.

    Protein is set from bodyweight (goal-dependent g/kg), fat takes a fixed share of kcal but never
    below its g/kg floor, and carbs fill the remainder. If protein + fat already exceed ``kcal``
    (an aggressive floor case), carbs clamp to zero rather than going negative.
    """
    protein_per_kg = c.PROTEIN_G_PER_KG.get(goal_type.strip().lower(), c.DEFAULT_PROTEIN_G_PER_KG)
    protein_g = weight_kg * protein_per_kg
    fat_g = max(kcal * c.FAT_FRACTION_OF_KCAL / c.KCAL_PER_G_FAT, weight_kg * c.FAT_FLOOR_G_PER_KG)

    remaining_kcal = kcal - protein_g * c.KCAL_PER_G_PROTEIN - fat_g * c.KCAL_PER_G_FAT
    carbs_g = max(remaining_kcal / c.KCAL_PER_G_CARB, 0.0)
    return protein_g, carbs_g, fat_g


def compute_targets(profile: BodyProfile) -> Targets:
    """Full pipeline: BMR → TDEE → goal-adjusted kcal → macro split."""
    maintenance = tdee(
        profile.weight_kg,
        profile.height_cm,
        profile.age,
        profile.sex,
        profile.activity_level,
    )
    kcal = goal_adjusted_kcal(maintenance, profile.rate_kg_per_week)
    protein_g, carbs_g, fat_g = macro_split(kcal, profile.weight_kg, profile.goal_type)
    return Targets(kcal=kcal, protein_g=protein_g, carbs_g=carbs_g, fat_g=fat_g)

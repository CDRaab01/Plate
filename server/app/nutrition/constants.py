"""Nutrition constants.

Phase 2 shows daily totals against a **static placeholder target** — the real per-user targets
engine (Mifflin-St Jeor BMR/TDEE, goal adjustment, macro split) arrives in Phase 3 and will
replace this. Kept here so there are no magic numbers inline (CLAUDE.md §7, §11).
"""
from app.nutrition.totals import Totals

# A neutral ~2000 kcal day with a balanced macro split, used until Phase 3 computes real targets.
STATIC_DAILY_TARGET = Totals(
    kcal=2000.0,
    protein_g=150.0,
    carbs_g=200.0,
    fat_g=67.0,
)

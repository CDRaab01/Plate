"""Sanity bounds for user-supplied body measurements.

Bounds are in **canonical units** (kg for weight, percent for bodyfat), since inbound values are
converted to canonical before validation (see :mod:`app.nutrition.units`). They reject absurd input
(typos, wrong-unit entries) at the schema layer with a 422; they are not a substitute for the
unit-system handling. Mirrors Spotter's ``app/limits.py`` but in kg (Plate stores weight in kg).
"""

# (min, max) kilograms. ~20 kg covers small children; 500 kg is well past any real adult.
BODY_WEIGHT_BOUNDS_KG = (20.0, 500.0)

# (min, max) body-fat percent.
BODYFAT_BOUNDS = (1.0, 70.0)

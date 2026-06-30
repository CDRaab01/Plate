"""Unit conversions + the user's unit-system preference (CLAUDE.md §11).

Storage is **canonical metric** everywhere — bodyweight in kg, food quantities in grams — so the
targets engine (:mod:`app.nutrition.targets`) and portion scaling stay metric and untouched.
Imperial (lb / oz) is a *display + input* concern only: requests are converted to canonical units
at the edge before anything is stored, and responses are formatted back to the user's preference.

These are pure, exact linear conversions (table-tested), deliberately kept out of the targets math
the §11 guard is about — but co-located here so the one set of factors is reused server-side (e.g.
the coach context formats weight per preference) and mirrored by the Android client.
"""

# Exact definitions (NIST): 1 lb = 0.45359237 kg; 1 oz = 28.349523125 g.
KG_PER_LB = 0.45359237
G_PER_OZ = 28.349523125

# The user's unit_system preference (stored in users.settings JSON). Default is imperial: this is a
# US-first personal app, so a new user with no preference set sees lb / oz.
UNIT_SYSTEMS = ("imperial", "metric")
DEFAULT_UNIT_SYSTEM = "imperial"

# The weight unit each system displays/inputs. (Food quantity uses oz vs g, handled at the UI.)
WEIGHT_UNIT_FOR_SYSTEM = {"imperial": "lb", "metric": "kg"}


def lb_to_kg(lb: float) -> float:
    """Pounds → kilograms (canonical storage unit for bodyweight)."""
    return lb * KG_PER_LB


def kg_to_lb(kg: float) -> float:
    """Kilograms → pounds (for display when the user prefers imperial)."""
    return kg / KG_PER_LB


def oz_to_g(oz: float) -> float:
    """Ounces → grams (canonical unit portion scaling works in)."""
    return oz * G_PER_OZ


def g_to_oz(g: float) -> float:
    """Grams → ounces (for display when the user prefers imperial)."""
    return g / G_PER_OZ


def weight_to_kg(weight: float, unit: str) -> float:
    """Convert an inbound bodyweight in ``unit`` ("lb" | "kg") to canonical kg.

    Raises :class:`ValueError` for an unsupported unit.
    """
    key = unit.strip().lower()
    if key == "kg":
        return weight
    if key == "lb":
        return lb_to_kg(weight)
    raise ValueError(f"unsupported weight unit: {unit!r}")


def kg_to_display(kg: float, unit_system: str) -> float:
    """Canonical kg → the value shown for ``unit_system`` (imperial → lb, metric → kg)."""
    return kg_to_lb(kg) if unit_system == "imperial" else kg

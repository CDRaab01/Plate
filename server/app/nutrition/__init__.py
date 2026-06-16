"""Centralized, pure macro math (CLAUDE.md §4, §7).

Everything here is pure and exhaustively unit-tested so clients only ever display numbers, never
recompute them:

* ``portions`` — turn a logged quantity/unit into a macro snapshot.
* ``totals`` — sum a day's snapshots.
* ``targets`` — the Phase 3 engine: BMR/TDEE, goal adjustment, and the macro split.
* ``constants`` — the single home for every coefficient the above depend on.

The training-day bump (Spotter-awareness) joins ``targets`` in Phase 7.
"""

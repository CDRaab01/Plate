"""Centralized, pure macro math (CLAUDE.md §4, §7).

Phase 2 introduces portion scaling (turning a logged quantity into a macro snapshot) and daily
totals. The targets engine (BMR/TDEE, goal adjustment, macro split) lands in Phase 3 in this same
package so all macro math stays in one place and clients only ever display, never recompute.
"""

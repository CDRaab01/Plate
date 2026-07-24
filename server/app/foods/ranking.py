"""Relevance ranking for food search results (CLAUDE.md §5).

External sources (USDA, OFF) return results in their own order. On a first-time query the local
``foods`` cache is empty, so that raw source order is exactly what the user sees — and a branded
product that merely *mentions* the query deep in a long description ("…black olives and marinated
banana peppers on a gyro pizza crust…") can outrank the literal food ("Black olives"). This module
scores each result by how well its **name** matches the query so the obvious food ranks first.

Pure and table-tested; no I/O. Ordering only — it never drops results.
"""

import re

_WORD = re.compile(r"[a-z0-9]+")


def _tokens(text: str) -> list[str]:
    return _WORD.findall(text.lower())


def relevance_score(name: str, query: str) -> float:
    """How well ``name`` matches ``query`` — higher is better.

    Tiers, not a continuous metric: tuned so an exact or prefix name beats a food that only
    contains the query somewhere in a longer name, which in turn beats a partial-word match.
    Ties inside a tier are broken by the caller (shorter/earlier names first).
    """
    query_tokens = _tokens(query)
    if not query_tokens:
        return 0.0
    name_l = name.strip().lower()
    query_norm = " ".join(query_tokens)

    if name_l == query_norm:
        return 100.0
    if name_l.startswith(query_norm):
        return 80.0

    name_tokens = set(_tokens(name))
    if all(tok in name_tokens for tok in query_tokens):
        # Every query word appears as a whole word in the name (any order): "Turkey, ground" for
        # "ground turkey", or "Black olives" for "black olives".
        return 60.0
    if query_norm in name_l:
        # Contiguous substring that isn't a clean word split.
        return 40.0

    whole = sum(1 for tok in query_tokens if tok in name_tokens)
    if whole:
        return 20.0 * whole / len(query_tokens)
    # Last resort: a query word is a prefix of a name word (or vice versa) — "turkey" ~ "turkeys".
    partial = sum(
        1
        for tok in query_tokens
        if any(nt.startswith(tok) or tok.startswith(nt) for nt in name_tokens)
    )
    return 5.0 * partial / len(query_tokens)


def rank_foods(foods, query, recent_rank=None):
    """Order ``foods`` best-match-first for ``query``.

    A user's recently-logged foods (``recent_rank``: ``{food_id: rank}``, lower = more recent) stay
    on top — the quick-log ergonomic — then by name relevance, then shorter names, then
    alphabetically so ordering is stable and deterministic.
    """
    recent_rank = recent_rank or {}
    not_recent = len(recent_rank) + 1
    return sorted(
        foods,
        key=lambda f: (
            recent_rank.get(f.id, not_recent),
            -relevance_score(f.name, query),
            len(f.name),
            f.name.lower(),
        ),
    )

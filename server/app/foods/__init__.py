"""Food sourcing (Phase 2).

External nutrition providers (USDA FoodData Central, Open Food Facts) sit behind a common
:class:`~app.foods.base.FoodSource` abstraction. Search follows a **local-cache-first** strategy
(CLAUDE.md §5): the local ``foods`` table is consulted first, and only on a miss do we reach out
to the external sources, normalize their payloads, deduplicate, and cache the results locally.
"""

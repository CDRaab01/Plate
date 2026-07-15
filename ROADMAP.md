# ROADMAP.md — Plate (departing-engineer assessment, 2026-07-03)

Suite-wide items (backups, Pulse migration, SSO 2e) are in the host-level roadmap. Plate is the
most "complete" app in the suite — v1 through Phase 8 plus the weight-trend loop all shipped —
so this list is shorter and mostly about closing loops on data that already exists.

## Road to 1.0 (suite pivot, 2026-07-13) — Plate goes first

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code): every app must pass a
shared bar — onboarding, designed empty/loading/error states, motion/celebration + dark/light
parity, defined offline behavior, no dead settings, an on-device pass, gating screenshot
baselines, icon quality, truthful docs — with `versionName` 1.0.0 as the round's **last** commit.

**Plate has the suite's biggest gap between daily value and perceived quality** (the engine of a
serious tool inside the shell of a prototype: versionName still 0.1.0, no onboarding package,
`Components.kt` is 70 lines, empty states are bare text, ~no motion), so it goes first and gets
the most visible win. The slate, in order:

1. **Port the Spotter polish patterns** — branded Empty/Loading/Error states, onboarding,
   motion — as they land in Pulse (host ROADMAP3 Tier P), not as a hand-copied fork.
2. **Celebration moments** — macro goal hit (✓ Home wraps the calorie ring in `CelebrationPulse`
   when the goal is met) and **✓ logging streaks DONE 2026-07-15**: `DailyLog.streak` (consecutive
   days logged, one grace day) surfaces as a flame pill on the Home hero, with a weekly-milestone
   cheer. *Still open:* a bigger celebration flourish at milestones (motion), via shared Pulse primitives.
3. **The "metabolism dashboard"** — the adaptive-TDEE engine (shipped, below) presented the way
   MacroFactor presents it: what we observed, why targets moved, confidence — instead of one
   Home card. The premium feature is the *explanation*, and the engine already computes it.
4. ✓ **Quick-log ergonomics** — DONE 2026-07-15. **Recent foods** surface (`GET /log/recent-foods`)
   fills the search screen while the query is empty — one tap re-logs a staple with its last
   portion pre-filled; **Copy yesterday** (`POST /log/copy-day`) appears on an empty day to pull
   the previous day's meals in. Server-deduped/additive; VM + endpoint tests.
5. **Photo-estimate feedback delta** (loop-closer #1) — turns estimator accuracy into data.
6. Version 0.1.0 → **1.0.0** at the gate.

**Gap review 2026-07-14 (host ROADMAP3 additions — what a MacroFactor user would expect):**

7. **Voice logging** — "two eggs and a banana" → parsed, editable draft (the house AI law:
   suggest, never auto-commit). LM Studio is already the vision backend; this is a mic button,
   a transcription hop, and a strict-JSON prompt. No commercial app can do it private and free —
   the single most "wow per hour" feature available to Plate.
8. **Nutrition-label scan** — photo of the *label*, not the meal. Higher accuracy than dish
   estimation, reuses the existing vision pipeline end-to-end, and covers the barcode-miss case
   (imports, local brands).
9. **The weekly check-in ritual** — MacroFactor's retention engine: weigh-in trend + adaptive
   target adjustment presented as a Sunday *event* (celebration/motion, "here's what changed and
   why"), not a passive card. Pairs with Road-to-1.0 #3's metabolism dashboard.
10. **Remaining-macros ring widget** (host Tier W4 Pulse widget family) — the most-glanced
    number in the suite belongs on the home screen.
11. **Meal reminders / "nothing logged today"** via the suite push pipeline (host Tier W2b) —
    opt-in, quiet hours, never nagging by default.

## The one next-level feature worth building

✓ **Adaptive TDEE correction — SHIPPED 2026-07-04** (ROADMAP2 T3 #1): pure
`app/nutrition/adaptive.py` (energy-balance solve of observed maintenance from intake vs weight
change, confidence blend + deviation clamp) → `maintenance_override` in `compute_targets` →
`GET /goals/adaptive`, surfaced in the Home `AdaptiveTdeeCard`. Exactly as specced below (kept
for the record); remaining work is the presentation ("metabolism dashboard", Road to 1.0 #3).

<details><summary>original spec</summary>

Plate already has both halves: logged intake (food diary) and
`nutrition/trend.py`'s observed weight rate (`observed_rate_kg_per_week`, `classify_pace`).
Today targets come from the Mifflin-St Jeor *estimate*; the mature move is to correct that
estimate from observed reality — if the user eats at the target and the trend says the loss
rate is half the goal, their true TDEE is lower; nudge targets accordingly (bounded, slow EMA,
never a shock adjustment). This is what separates serious tools (MacroFactor) from calorie
counters, it's pure math in `nutrition/` (exhaustively testable, no AI), and it makes weeks 6+
of a cut actually work. Feed the adjustment rationale into the coach's context so it can
explain itself.

</details>

## Loop-closing improvements

1. **Photo-estimate feedback loop.** Photo logging stores the model's draft; the user edits
   before confirming. Persist the draft→confirmed delta (per food, per macro). Even a simple
   monthly report of estimator bias tells you whether prompt tuning is needed — right now
   accuracy is vibes.
2. **Food-cache staleness policy.** OFF/USDA rows are cached forever on first fetch; products
   get reformulated. Add a `fetched_at` + lazy re-fetch TTL (6–12 months) on barcode hits.
3. **USDA bulk import as a documented operator step.** `scripts/import_usda_bulk.py` exists —
   make sure the deployed DB actually ran it and record that in deploy/README (search quality
   silently degrades to live-API-with-key or OFF-only without it).
4. **Weekly summary → suite digest.** The `GET /log/summary` aggregation is the natural data
   source for the cross-app weekly digest idea (host ROADMAP Tier 3) — design any summary
   changes with that consumer in mind.
5. **Quick-log ergonomics:** "copy yesterday's breakfast" / recent-foods-first search ranking.
   Cheap, and it's the #1 real-world friction in daily logging apps.

## Cross-app work (approved 2026-07-03 — see Dragonfly/CROSS-APP.md for the full design)

Plate is the hub of the approved cross-app roadmap (nutrition is the shared language):

1. **Weight authority** (CROSS-APP item 1): add `GET/POST /cross-app/weight` so Spotter
   writes weigh-ins through and reads the merged series. Directly feeds the adaptive-TDEE
   feature above — do them together.
2. ✓ **Planned-dinner awareness — SHIPPED 2026-07-11** (federated-awareness Link E): the coach
   gets a "Planned meals today (reported by Cookbook)" context line; context only, never
   auto-logged.
3. ✓ **Remaining-macros surface — SHIPPED 2026-07-11** (Link F): `GET /cross-app/remaining?date=`
   feeds Cookbook's `fits_today` badge; 404-when-no-goal ⇒ no badge.
4. **Digest range read** (item 4): expose the existing `/log/summary?start=&end=` on the
   cross-app auth surface — the suite weekly digest (host ROADMAP3 Tier W1) is the consumer.

Follow the CROSS-APP.md infra rules: provider-committed contract fixtures under
`server/tests/contracts/`, and write new surfaces against a swappable token-verify helper
(the shared `CROSS_APP_SECRET` is slated for replacement by dragonfly-id service tokens).

## Coordination notes

- Plate is the **reference implementation Cookbook ports from** (its `app/services/ai/` vision
  pipeline seeded Cookbook's photo import, and a Cookbook AI round is planned) — if the photo
  pipeline or guardrail layer gets improved here, tell the Cookbook side (and vice versa);
  don't let the two forks drift silently.
- Plate serves Cookbook's cross-app endpoints (`/recipes/export`, `/cross-app/*`) — breaking
  changes there need a Cookbook release in the same window.

## Explicitly not worth it

- Micronutrient completionism (vitamins panel) — secondary fields cover the user's actual use.
- Water/step tracking — other tools do this; scope creep.
- A custom food-recognition model — re-litigated periodically, still wrong (see CLAUDE.md
  closed decisions).

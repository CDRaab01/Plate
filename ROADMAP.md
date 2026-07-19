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
   *Mostly DONE:* first-run onboarding (Pulse `OnboardingScaffold`), consistent empty states,
   and **designed error states DONE 2026-07-15** (Pulse `ErrorState` on the full-screen
   load-error surfaces — Diary/Goals/Recipe list). *Still open:* the milestone motion flourish.
2. **Celebration moments** — macro goal hit (✓ Home wraps the calorie ring in `CelebrationPulse`
   when the goal is met) and **✓ logging streaks DONE 2026-07-15**: `DailyLog.streak` (consecutive
   days logged, one grace day) surfaces as a flame pill on the Home hero, with a weekly-milestone
   cheer. *Still open:* a bigger celebration flourish at milestones (motion), via shared Pulse primitives.
3. ✓ **The "metabolism dashboard"** — DONE 2026-07-15. The adaptive-TDEE engine (shipped, below)
   presented the MacroFactor way: what we observed, why targets moved, confidence — a full
   `ui/metabolism/` dashboard (`MetabolismScreen`/`MetabolismViewModel`) opened by tapping the
   Home metabolism card, with the pre-unlock learning state covered by screenshot baselines,
   instead of one Home card. The premium feature is the *explanation*, and the engine already
   computed it.
4. ✓ **Quick-log ergonomics** — DONE 2026-07-15. **Recent foods** surface (`GET /log/recent-foods`)
   fills the search screen while the query is empty — one tap re-logs a staple with its last
   portion pre-filled; **Copy yesterday** (`POST /log/copy-day`) appears on an empty day to pull
   the previous day's meals in. Server-deduped/additive; VM + endpoint tests. **Also: `/foods/search`
   now ranks the user's recently-logged matches first (then alphabetical)** so re-logging a staple
   is a top hit, not buried — the "recent-foods-first ranking" half (server-only, per-user, tested).
5. **Photo-estimate feedback delta** (loop-closer #1) — turns estimator accuracy into data.
6. Version 0.1.0 → **1.0.0** at the gate.

**Gap review 2026-07-14 (host ROADMAP3 additions — what a MacroFactor user would expect):**

7. ✓ **Voice logging** — DONE 2026-07-16. "two eggs and a banana" → parsed, editable draft (the
   house AI law: suggest, never auto-commit). Speech→text is **on-device** (`RecognizerIntent`,
   no audio leaves the phone); the server (`POST /foods/voice`) runs a strict-JSON LM Studio parse,
   resolves each spoken food against the trusted food search for real macros, and returns the
   shared draft editor. Private and free — no commercial app does this.
8. ✓ **Nutrition-label scan** — DONE 2026-07-15. Photo of the *label*, not the meal
   (`POST /foods/label`): the same vision seam with a label-specific transcription prompt (one
   food = one serving), reusing the `PhotoEstimateResponse` draft + the never-auto-committed
   guarantee. Higher accuracy than dish estimation; covers the barcode-miss case (imports,
   local brands). Photo / label / voice share entry points on the food-search top bar.
9. **The weekly check-in ritual** — MacroFactor's retention engine: weigh-in trend + adaptive
   target adjustment presented as a Sunday *event* (celebration/motion, "here's what changed and
   why"), not a passive card. Pairs with Road-to-1.0 #3's metabolism dashboard.
   *Server core DONE 2026-07-15:* `GET /checkin/weekly` composes days-logged-this-week, the
   ~7-day weight move, and the adaptive-TDEE read (`checkin_service`, tested). Remaining: the
   client "Sunday event" presentation + a weekly "due" trigger.
10. ✓ **Remaining-macros home-screen widget** — DONE 2026-07-15. A Glance app widget
    (`widget/PlateWidget.kt`) shows today's remaining macros on the launcher, fed by a
    `SnapshotStore` that the diary/home VMs stamp on every change (`WidgetSnapshotWriter`/
    `WidgetRefresher`). The most-glanced number in the suite now lives on the home screen.
11. ✓ **Meal reminders / "nothing logged today"** — DONE 2026-07-15. Opt-in retention nudges,
    **client-only** (no server push): two meal reminders + an evening "nothing logged today"
    nudge via self-rescheduling inexact AlarmManager alarms (`util/nudges/`), with quiet hours,
    reboot restore, and the runtime notifications-permission gate. Never nagging by default.

12. ✓ **Static launcher shortcuts** — DONE 2026-07-15 (`res/xml/shortcuts.xml`): long-press the
    icon for Log food / Scan barcode / Snap a meal, routed through `MainActivity` → `PlateNavHost`.

**What genuinely remains for 1.0 (2026-07-16):** the polish round has landed nearly all the
feature work above — metabolism dashboard, voice logging, label scan, the macro widget, retention
nudges, launcher shortcuts, error states, quick-log ergonomics, logging streaks, and the
cross-app summary read are all shipped, and a data-correctness bug (a "Cut" goal that stored a
positive rate served a *surplus*) was healed at the source — `goal_type` is now authoritative for
direction (`schemas/goal.py` `normalize_rate_sign`: cut → −|rate|, bulk → +|rate|, maintain → 0),
the goal screen takes a positive magnitude, and **migration 0005** back-fills the invariant onto
existing rows on boot. What's left is the **1.0 gate**, not new features:

- The **on-device pass** (host ROADMAP3 gate item) — exercise every surface on real hardware.
- The **weekly check-in "Sunday event"** client presentation + weekly "due" trigger (#9 above;
  server core already shipped).
- The **milestone celebration motion** flourish (#2) and the **photo-estimate feedback delta**
  (#5 / loop-closer #1) — both nice-to-have, not gate-blocking.
- **`versionName` 0.1.0 → 1.0.0** (#6) as the round's **last** commit. *Still 0.1.0 today.*

## Restaurant / chain meal logging — SHIPPED 2026-07-18 (owner request)

✓ The "Salsa Grille bowls" feature (requested by the household's second user): a **Restaurant**
is a checkbox template of categorized build-your-own components — tap the restaurant, tick what
was in the bowl, adjust portions, one diary entry per component (`log_recipe` semantics).
Restaurants default **shared** across the server's accounts (build a chain once, everyone logs
from it; owner-only edits). Three ways to build one: **paste a menu link** (server fetch → LM
Studio structured parse → editable draft; page-stated nutrition carried verbatim as minted
official foods, else USDA-generic estimates via trusted search — the voice-pipeline pattern),
**manual build** (embedded food search), or **bundled chain presets** (transcribed from official
nutrition publications). Migration 0006; `/restaurants` API; `ui/restaurant/`; 46 new server
tests + VM/parser unit tests. **Presets shipped: Chipotle, Qdoba, Moe's Southwest Grill, CAVA,
Subway, Panda Express, Culver's** (7 chains, 159 components). Menu parsing also accepts **pasted
text** (not just a URL), so any chain whose nutrition is on a JS page can be added by pasting.

*Still open from this round:*
- **Image-only menu PDFs** (Salsa Grille's own menu is a designed PDF that may carry no
  extractable text — the parse 422s toward the manual builder): render PDF pages to images
  through the existing vision pipeline so even rasterized menus parse.
- **More presets** as the household visits more chains — `assets/restaurant_presets.json` is the
  only file to touch (PresetParserTest gates its shape). Added Moe's / CAVA / Subway 2026-07-18,
  Panda Express / Culver's 2026-07-19 (macros from FatSecret's server-rendered tables — the
  chains' own nutrition pages are JS-walled). Next candidates the owner asked for: Taco Bell,
  McDonald's, Wendy's, Dairy Queen, Steak 'n Shake, Blaze Pizza, McAlister's (paste-text or
  per-item sourcing).
- **Restaurant-labeled diary entries** ("Salsa Grille: Cilantro Lime Rice" instead of the linked
  food's name) — needs a decision on widening `FoodLogEntry.name` beyond quick-add.

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
4. ✓ **Weekly summary → suite digest — SHIPPED 2026-07-16.** Delivered as the cross-app
   `GET /cross-app/summary?start=&end=` window read (see Cross-app work #4) rather than exposing
   `/log/summary` directly — the digest gets aggregates only, no raw diary.
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
4. ✓ **Digest range read — SHIPPED 2026-07-16** (item 4): `GET /cross-app/summary?start=&end=`
   on the cross-app auth surface (RS256/HS256 token, email-resolved, 401 when unset — mirrors
   `/cross-app/remaining`). Returns window aggregates only — days_logged, days_in_window,
   avg_calories, calorie/protein adherence %, weight_change_kg (adherence reuses the targets
   engine over logged days that have a goal; ±10% calorie band, protein ≥ target; window capped
   at 92 days). The suite weekly digest (host ROADMAP3 Tier W1) is the consumer.

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

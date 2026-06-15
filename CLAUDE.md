# CLAUDE.md — "Plate" (working name)

> A calorie & macro tracking app, built as a sister application to **Spotter**
> (https://github.com/CDRaab01/Spotter). Same stack, same theme, same conventions.
> Think MyFitnessPal + an on-device AI coach, with Spotter workout-awareness baked in.

---

## 0. Read this first

This file is the source of truth for the build. Work **phase by phase**. Do not
start a later phase before the earlier one's exit criteria (tests green, CI green)
are met. When a decision is ambiguous, **match Spotter's existing choice** —
inspect the Spotter repo for its patterns (package layout, DI, networking,
serialization, theming, test framework) and mirror them. Consistency between the
two apps is a hard requirement.

Before writing code in any phase: restate the phase goal, list the files you'll
touch, flag any assumption, then proceed.

---

## 1. Product summary

Plate lets a user log food and track **calories, protein, carbs, and fat** (with
cholesterol and a few other nutrients tracked as secondary fields). Core features:

1. **Food logging** split into **Breakfast / Lunch / Dinner / Snacks**.
2. **Food database** search backed by USDA FoodData Central (whole foods) +
   Open Food Facts (packaged goods & barcodes).
3. **Barcode scanning** → on-device scan → Open Food Facts lookup.
4. **Photo logging** → take a photo of a meal → vision model estimates the food
   and macros → user confirms/edits before it's logged.
5. **AI coach** (chat) using **Gemma 3** via **LM Studio**, same as Spotter's
   chat. Talks recipes, food swaps, hitting macro targets.
6. **Spotter-awareness**: the coach and the daily targets adjust based on whether
   the user trained that day (raise intake) or is in a cut (lower intake).
7. **Real user accounts**, same auth approach as Spotter.

---

## 2. Stack (must match Spotter exactly)

- **Client:** Android, Kotlin. Mirror Spotter's architecture (e.g. MVVM +
  repository, Jetpack Compose if Spotter uses it, Hilt/Koin per Spotter, Retrofit/
  Ktor per Spotter, Coroutines/Flow).
- **Backend:** Python **FastAPI**, same project layout and tooling as Spotter
  (same lint/format, same dependency manager, same test runner).
- **LLM inference:** **LM Studio** serving **Gemma 3**, same client wiring as
  Spotter's chat feature. Local only — no hosted model fallback.
- **DB:** Match Spotter (Postgres if it uses Postgres; otherwise its choice).
  If Spotter is single-DB, **share the same database/instance** (see §8).
- **Auth:** Reuse Spotter's auth mechanism and, ideally, its user table.

> ⚠️ Do not introduce a new framework, language, or major library that Spotter
> doesn't already use without flagging it first.

---

## 3. Premise notes / decisions already made

- **No custom CV model training.** Photo→macros uses the **vision-capable Gemma 3
  model via LM Studio** (or whatever multimodal model Spotter's LM Studio setup
  exposes). Training a bespoke food-recognition model is out of scope: worse
  accuracy for months of effort. If LM Studio's model can't do vision acceptably,
  treat photo-logging as a best-effort *estimate* the user must confirm — never
  auto-commit photo-derived macros.
- **Third macro is carbs**, not cholesterol. Cholesterol is tracked as a
  secondary nutrient alongside fiber, sugar, sodium, saturated fat.
- **Spotter exposes nothing today.** Plan of record: **shared backend** — both
  apps are clients of one FastAPI service with a shared user/account table, so
  Plate reads workout data directly. Fallback if shared backend is rejected: add
  a small read-only `/workouts` endpoint to Spotter. (See §8; confirm before
  building the integration.)

---

## 4. Data model (backend)

Tables (adapt names/types to Spotter's conventions and migration tooling):

- `users` — **reuse Spotter's** if shared backend.
- `foods` — canonical food/nutrition records. Source-tagged (`usda` | `off` |
  `user`). Nutrition stored **per 100g and per serving**. Fields: kcal, protein_g,
  carbs_g, fat_g, fiber_g, sugar_g, sat_fat_g, cholesterol_mg, sodium_mg,
  serving_size, serving_unit, brand, barcode (nullable, unique-ish), source,
  source_id.
- `food_log_entries` — user_id, food_id, date, meal (`breakfast|lunch|dinner|
  snack`), quantity, unit, computed macros snapshot (denormalized so edits to the
  source food don't rewrite history).
- `daily_targets` — user_id, date, kcal/protein/carbs/fat targets (computed, see §7).
- `user_goals` — goal type (`maintain|cut|bulk`), weight, height, age, sex,
  activity baseline, rate (e.g. −0.5 kg/wk).
- `recipes` (later phase) — user-saved meals = a named set of food entries.

All macro math lives in **one backend module** (`nutrition/`), unit-tested
exhaustively. Clients never recompute targets independently.

---

## 5. External data sources

### Open Food Facts (packaged + barcodes)
- Base: `https://world.openfoodfacts.org/api/v2/product/{barcode}.json`
- No API key. **ODbL license → must display attribution** ("Data from Open Food
  Facts, ODbL") in the app's about/credits screen.
- Use the `fields=` query param to request only needed nutriments
  (`product_name,brands,nutriments,serving_size`).
- Set a descriptive `User-Agent` (OFF requires it): `Plate/<version> (<contact>)`.
- **Rate-limit & cache:** never hit OFF live per keystroke. Cache lookups into our
  `foods` table on first fetch; serve from DB after.

### USDA FoodData Central (whole foods)
- Requires a **free API key** (api.data.gov). Store server-side only, never ship
  it in the APK.
- Use for generic/whole foods ("chicken breast", "banana").
- Consider a **bulk import** of the FDC "Foundation" + "SR Legacy" datasets into
  `foods` at deploy time so common searches are local and fast; fall back to live
  API for misses.

**Search strategy:** local `foods` table first → USDA API → OFF. Barcode path
goes straight to OFF (then cache). Deduplicate by barcode and by normalized name.

---

## 6. Feature flows

**Barcode scan:** ML Kit barcode scanning on-device → barcode string → backend
`/foods/barcode/{code}` → OFF lookup + cache → return normalized food → user sets
quantity & meal → log.

**Photo logging:** capture/select image → backend `/foods/photo` → send image to
LM Studio vision model with a strict prompt: "Identify each distinct food, estimate
portion, return JSON only: `[{name, est_grams, kcal, protein_g, carbs_g, fat_g,
confidence}]`." Parse, **show as an editable draft**, user confirms → log.
Never auto-commit. Low confidence → prompt user to refine or search manually.

**AI coach:** chat screen mirroring Spotter's. System prompt gives the model the
user's remaining macros for the day, their goal, and **today's Spotter training
status**. It answers recipe/swap/portion questions and can propose foods that fit
remaining macros. Keep tools/structured output if Spotter's chat uses them.

---

## 7. Targets & Spotter-awareness (the math)

- Base maintenance via Mifflin-St Jeor (BMR × activity factor). Adjust by goal:
  cut = deficit, bulk = surplus, from `user_goals.rate`.
- Protein target from bodyweight (e.g. 1.6–2.2 g/kg, configurable). Fat floor,
  carbs fill remainder of kcal.
- **Training-day adjustment:** if Spotter reports a workout for `date`, add an
  intake bump (configurable kcal/macros, default skewed to carbs+protein). The
  coach is told "user trained today" and reflects it.
- All of this is **pure functions in `nutrition/`**, fully unit-tested with table
  cases. No magic numbers inline — constants in one config module.

---

## 8. Spotter integration (CONFIRM BEFORE BUILDING)

Plan of record: **shared backend + shared `users` table**, Plate queries the same
DB for workout records. This avoids duplicating accounts and gives real-time
training status.

If kept separate instead: add a minimal authenticated **`GET /workouts?date=`**
read-only endpoint to Spotter and have Plate call it. Document whichever path is
chosen at the top of the backend README. **Do not build this phase until the
human confirms the integration shape.**

---

## 9. Build phases (work in order; each ends with green tests + green CI)

**Phase 0 — Scaffolding & CI/CD**
- Create repo matching Spotter's structure. Backend FastAPI skeleton + Android
  skeleton. Set up the **same** linters/formatters/test runners as Spotter.
- GitHub Actions: lint + unit tests on PR for both backend and Android; build the
  APK; cache deps. Branch protection requires green CI.
- Exit: empty app builds, CI green, health-check endpoint + a trivial passing test
  on each side.

**Phase 1 — Accounts & data model**
- Auth mirroring Spotter (reuse its user table if shared backend). Migrations for
  `foods`, `food_log_entries`, `user_goals`, `daily_targets`.
- Exit: user can register/log in; schema migrates cleanly; repository-layer tests
  pass.

**Phase 2 — Food search + manual logging + meal split**
- USDA + OFF integration behind a `FoodSource` abstraction with the local-cache-
  first strategy. Search UI. Log entries into Breakfast/Lunch/Dinner/Snacks.
  Daily totals vs (static for now) targets.
- Exit: full manual log/edit/delete flow; integration tests with **mocked**
  external APIs; daily totals correct under unit test.

**Phase 3 — Targets engine + goals**
- `nutrition/` module: BMR/TDEE, goal adjustment, macro split. Goals UI.
- Exit: targets computed and shown; exhaustive unit tests on the math.

**Phase 4 — Barcode scanning**
- ML Kit scanner + `/foods/barcode` + OFF caching + attribution screen.
- Exit: scanning a real product logs it; backend caches; tests with mocked OFF.

**Phase 5 — AI coach (Gemma 3 / LM Studio)**
- Chat feature mirroring Spotter's wiring. System prompt includes remaining macros
  + goal. (Spotter-awareness added in Phase 7.)
- Exit: coach answers food/recipe questions; LM Studio client covered by tests
  with a mocked inference server.

**Phase 6 — Photo logging (vision)**
- `/foods/photo` → vision model → editable draft → confirm → log. Strict JSON
  contract, robust parsing, low-confidence handling.
- Exit: photo produces an editable estimate that never auto-commits; parsing
  covered by tests including malformed-model-output cases.

**Phase 7 — Spotter-awareness** *(gated on §8 confirmation)*
- Wire workout status into targets (training-day bump) and into the coach prompt.
- Exit: a logged Spotter workout visibly changes today's targets and the coach's
  framing; integration tested with a stubbed workout source.

**Phase 8 — Recipes / saved meals + polish**
- Saved meals/recipes, quick-add, weekly summaries, theming pass to match Spotter.
- Exit: feature-complete v1, full CI green, README + attribution complete.

---

## 10. Testing & CI/CD requirements

- **Backend:** unit tests for all `nutrition/` math (table-driven), repository
  tests against a test DB, API tests with external sources **mocked** (no live
  USDA/OFF/LM Studio calls in CI). Coverage gate matching Spotter's.
- **Android:** unit tests for view models/repositories; instrumented/UI tests for
  the critical log + scan flows.
- **CI (GitHub Actions):** on every PR — lint, format-check, unit tests (both
  sides), Android assembleDebug. Block merge on failure. Mirror Spotter's workflow
  files where possible.
- **CD:** tagged release builds a signed-ish debug/internal APK artifact and (if
  Spotter does) deploys the backend. Match Spotter's deployment target.
- No secrets in the repo. USDA key, LM Studio URL, DB creds via env/secrets only.

---

## 11. Conventions & guardrails

- Match Spotter's code style, package naming, commit style, and PR size norms.
- Macro math is centralized and pure; clients display, never compute targets.
- External nutrition data is cached, attributed (OFF/ODbL, USDA), and rate-limited.
- Photo- and AI-derived numbers are always **estimates the user confirms**.
- Keep PRs scoped to a single phase deliverable. Restate assumptions before coding.
- If something here conflicts with how Spotter actually does it, **Spotter wins** —
  flag the conflict in the PR description.

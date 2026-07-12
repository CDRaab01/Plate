from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 7

    # Security hardening for public (e.g. Cloudflare Tunnel) multi-user deployments.
    # When set, /auth/register requires a matching invite_code. Leave unset for an open
    # (local/dev or trusted-network) deployment.
    registration_invite_code: str | None = None
    # Trust X-Forwarded-For / CF-Connecting-IP for the rate-limit client key. Only enable
    # behind a trusted reverse proxy (Cloudflare Tunnel, nginx) — otherwise clients can spoof it.
    trust_proxy: bool = False
    # Emit Strict-Transport-Security. Enable only when served over HTTPS (TLS at the proxy/edge).
    hsts_enabled: bool = False
    # Expose the interactive API docs (/docs, /redoc, /openapi.json). Disable on public deploys.
    docs_enabled: bool = True

    # Build/deploy stamp surfaced by GET /version so the app can show what's running
    # (and confirm a redeploy landed). Injected at deploy time; "unknown" for a manual/dev run.
    git_sha: str = "unknown"
    built_at: str = "unknown"

    # External food data sources (Phase 2). Keys/contact are server-side only — never shipped in
    # the APK (CLAUDE.md §5). Live calls happen only on a local-cache miss; results are cached into
    # the `foods` table and served locally thereafter.
    usda_api_key: str | None = None
    usda_base_url: str = "https://api.nal.usda.gov/fdc/v1"
    off_base_url: str = "https://world.openfoodfacts.org"
    # OFF requires a descriptive User-Agent identifying the app + a contact (CLAUDE.md §5).
    off_user_agent: str = "Plate/0.1.0 (cdraab01@gmail.com)"
    # Per-source cap on how many results a single live search pulls back (and the "cache is rich
    # enough, skip the network" threshold in food_service.search_foods).
    external_search_limit: int = 25
    # Timeout (seconds) for outbound calls to USDA/OFF.
    external_timeout_seconds: float = 8.0
    # Master switch for live external lookups on a cache miss. Disabled in the test suite so CI
    # never reaches the network (CLAUDE.md §10); local-cache search still works with it off.
    food_search_live: bool = True

    # External recipe discovery (Spoonacular). Server-side key only. Unset ⇒ the discover/import
    # endpoints are disabled (503). Free key: https://spoonacular.com/food-api/console#Dashboard
    spoonacular_api_key: str | None = None
    spoonacular_base_url: str = "https://api.spoonacular.com"
    # Use Spoonacular via RapidAPI instead of direct: set this to the RapidAPI host
    # (spoonacular-recipe-food-nutrition-v1.p.rapidapi.com) and put the RapidAPI key in
    # spoonacular_api_key. Auth then uses X-RapidAPI-Key/Host headers, not the apiKey param.
    spoonacular_rapidapi_host: str | None = None
    # Max recipes returned per discovery search.
    recipe_discover_limit: int = 10

    # Optional SMTP — if unset, reset codes are printed to stdout instead
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_from: str = "noreply@plate.local"
    # True = SSL on port 465 (Outlook, Yahoo). False (default) = STARTTLS on port 587 (Gmail).
    smtp_use_ssl: bool = False

    # AI coach via LM Studio serving Gemma 3 (CLAUDE.md §2, §6). Local-only inference — no hosted
    # fallback. Mirrors Spotter's chat wiring: the OpenAI-compatible /chat/completions endpoint.
    # The base URL includes the API prefix (LM Studio defaults to /v1). The URL/model are
    # deployment config; CI never reaches a real server (the client is injected in tests).
    lm_studio_base_url: str = "http://localhost:1234/v1"
    lm_studio_model: str = "google/gemma-3-12b"
    # Generous timeout: the first request after a cold start pays the model-load cost.
    lm_studio_timeout: float = 60.0

    # Spotter-awareness (Phase 7, CLAUDE.md §7, §8). Plate reads the user's training status from
    # Spotter's read-only GET /workouts and bumps that day's targets when they trained. Auth is a
    # short-lived JWT signed with `cross_app_secret` carrying the user's email; Spotter validates it
    # with the SAME secret and resolves its own user by email. Both settings unset ⇒ the integration
    # is disabled (NullWorkoutSource: never a training day), which is the case in CI and any deploy
    # without Spotter. `cross_app_secret` here MUST equal Spotter's `cross_app_secret`.
    spotter_base_url: str | None = None
    cross_app_secret: str | None = None
    # Cross-app service token (ROADMAP T2 #5, retiring cross_app_secret). When these are set (with
    # suite_issuer), Plate requests a short-lived RS256 token from the Dragonfly identity server's
    # POST /cross-app/token instead of self-signing an HS256 token — Spotter validates it via the
    # JWKS it already trusts. Falls back to cross_app_secret when unset (dual-accept transition).
    cross_app_client_id: str | None = None
    cross_app_client_secret: str | None = None

    # Suite SSO (BROKER.md Phase 2b). When suite_jwks_url + suite_issuer are set, POST /auth/suite
    # accepts a suite access token (RS256, from the Dragonfly identity server), validates it
    # against the published JWKS, and trades it for a Plate session — linking by email. Unset ⇒
    # the endpoint is disabled and the app's own email/password login is unaffected (dual-auth).
    suite_jwks_url: str | None = None
    suite_issuer: str | None = None
    suite_audience: str = "suite"
    # TTL of the minted cross-app token — only needs to outlive a single request round-trip.
    cross_app_token_ttl_seconds: int = 60
    # Timeout (seconds) for the outbound call to Spotter. A failure degrades gracefully to
    # "not a training day" rather than failing the user's request.
    spotter_timeout_seconds: float = 8.0

    # Cookbook awareness (federated awareness Link E, CROSS-APP.md rule 7): the coach reads
    # tonight's planned meal so it can pace the day around it. Unset ⇒ no plan line (NullPlanSource);
    # RS256-only, reusing the cross_app_client_id/secret above. Same timeout shape as Spotter.
    cookbook_base_url: str | None = None
    cookbook_timeout_seconds: float = 8.0

    # Photo logging (Phase 6, CLAUDE.md §6). The vision model estimates the foods + macros in a
    # meal photo; the user always confirms before anything is logged (never auto-committed). Defaults
    # to the same multimodal Gemma 3 the coach uses — override if a separate vision model is loaded.
    lm_studio_vision_model: str = "google/gemma-3-12b"
    # Reject uploads larger than this before sending to the model (a meal photo is comfortably under
    # this; the cap bounds memory + the base64 payload to LM Studio).
    photo_max_bytes: int = 8 * 1024 * 1024
    # Estimates at or below this confidence (0–1) flag the draft as low-confidence so the client can
    # nudge the user to refine or search manually (CLAUDE.md §6).
    photo_low_confidence_threshold: float = 0.4


settings = Settings()

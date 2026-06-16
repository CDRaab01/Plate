from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

from app.config import settings
from app.limiter import limiter
from app.routers import ai, auth, foods, goals, log, recipes, users

# Single source for the human-facing version, reused by GET /version below.
APP_VERSION = "0.1.0"

# Interactive docs are handy locally but are an unnecessary surface on a public deployment.
app = FastAPI(
    title="Plate API",
    version=APP_VERSION,
    docs_url="/docs" if settings.docs_enabled else None,
    redoc_url="/redoc" if settings.docs_enabled else None,
    openapi_url="/openapi.json" if settings.docs_enabled else None,
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# CORS: credentials=True is incompatible with wildcard origins (browser spec) and
# unnecessary for the Android client which uses Authorization Bearer headers.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def security_headers(request: Request, call_next) -> Response:
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    if settings.hsts_enabled:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response


app.include_router(auth.router)
app.include_router(users.router)
app.include_router(foods.router)
app.include_router(goals.router)
app.include_router(log.router)
app.include_router(recipes.router)
app.include_router(ai.router)


@app.get("/health", tags=["health"])
async def health() -> dict:
    return {"status": "ok"}


@app.get("/version", tags=["version"])
async def version() -> dict:
    # Unauthenticated (like /health) so the app can show what's running before/after login.
    return {
        "name": app.title,
        "version": APP_VERSION,
        "commit": settings.git_sha,
        "built_at": settings.built_at,
    }

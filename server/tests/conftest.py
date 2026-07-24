import asyncio
import uuid

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from app.config import settings
from app.database import AsyncSessionLocal, Base, engine
from app.limiter import limiter
from app.main import app
from app.models.food import Food

# Disable rate limiting for the test suite so rapid registrations don't
# trigger the 5/minute cap on /auth/register.
limiter.enabled = False

# Guarantee CI never reaches the network: live USDA/OFF lookups are off for the whole suite
# (CLAUDE.md §10). External behavior is exercised with mocked transports / fake sources instead.
settings.food_search_live = False


@pytest.fixture(scope="session")
def event_loop():
    """Share a single event loop across the whole test session.

    The app's engine is a module-level singleton whose asyncpg connection pool
    binds to the first loop that touches it. Without a shared session loop,
    pytest-asyncio gives each test its own loop and every DB query raises
    "attached to a different loop". One session-scoped loop keeps the pool valid.
    """
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture(scope="session", autouse=True)
async def setup_tables():
    """Ensure all tables exist before any test runs (safe to call after alembic)."""
    async with engine.begin() as conn:
        # Tests build schema via create_all (not Alembic), so the pg_trgm extension migration
        # 0007 applies in production must be mirrored here for the fuzzy-search tests.
        await conn.execute(text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
        await conn.run_sync(Base.metadata.create_all)
    # Empty the pool so the connection opened above (bound to this setup step)
    # isn't later reused by a test running on a different loop.
    await engine.dispose()
    yield


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


@pytest_asyncio.fixture
async def auth_client(client):
    """HTTP client pre-authenticated as a fresh unique test user."""
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Test User",
            "email": f"test_{uid}@plate.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201, resp.text
    token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token}"
    return client


@pytest_asyncio.fixture
async def food():
    """A real food row inserted directly into the test DB."""
    async with AsyncSessionLocal() as session:
        f = Food(
            source="user",
            name=f"Test Banana {uuid.uuid4().hex[:6]}",
            kcal_per_100g=89.0,
            protein_g_per_100g=1.1,
            carbs_g_per_100g=22.8,
            fat_g_per_100g=0.3,
            serving_size=118.0,
            serving_unit="g",
        )
        session.add(f)
        await session.commit()
        await session.refresh(f)
        return f


@pytest_asyncio.fixture
async def recipe_food():
    """Same macro profile as ``food`` but a distinct name, so tests that build recipes/log entries
    don't add to the ``Test Banana`` population the food-search test counts on."""
    async with AsyncSessionLocal() as session:
        f = Food(
            source="user",
            name=f"Recipe Oats {uuid.uuid4().hex[:6]}",
            kcal_per_100g=89.0,
            protein_g_per_100g=1.1,
            carbs_g_per_100g=22.8,
            fat_g_per_100g=0.3,
            serving_size=118.0,
            serving_unit="g",
        )
        session.add(f)
        await session.commit()
        await session.refresh(f)
        return f

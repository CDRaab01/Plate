"""Repository-layer tests: exercise the Phase 1 data model directly against the DB.

These verify persistence, the denormalized log snapshot, FK delete behavior (CASCADE vs
SET NULL), and the daily-targets uniqueness constraint — the Phase 1 exit criteria.
"""
import datetime
import uuid

import pytest
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError

from app.database import AsyncSessionLocal
from app.models.daily_target import DailyTarget
from app.models.food import Food
from app.models.food_log_entry import FoodLogEntry
from app.models.user import User
from app.models.user_goal import UserGoal
from app.security import hash_password


async def _make_user(session) -> User:
    user = User(
        name="Repo Tester",
        email=f"repo_{uuid.uuid4().hex[:8]}@test.com",
        hashed_password=hash_password("secret123"),
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


async def test_food_persists_with_per_100g_and_serving(food):
    async with AsyncSessionLocal() as session:
        loaded = await session.get(Food, food.id)
        assert loaded is not None
        assert loaded.source == "user"
        assert loaded.kcal_per_100g == 89.0
        assert loaded.serving_size == 118.0
        assert loaded.serving_unit == "g"


async def test_log_entry_stores_denormalized_snapshot(food):
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        entry = FoodLogEntry(
            user_id=user.id,
            food_id=food.id,
            date=datetime.date(2026, 6, 16),
            meal="breakfast",
            quantity=118.0,
            unit="g",
            kcal=105.0,
            protein_g=1.3,
            carbs_g=27.0,
            fat_g=0.4,
        )
        session.add(entry)
        await session.commit()
        entry_id = entry.id

    async with AsyncSessionLocal() as session:
        loaded = await session.get(FoodLogEntry, entry_id)
        assert loaded is not None
        assert loaded.meal == "breakfast"
        assert loaded.kcal == 105.0
        assert loaded.food_id == food.id


async def test_deleting_user_cascades_to_log_entries(food):
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        session.add(
            FoodLogEntry(
                user_id=user.id,
                food_id=food.id,
                date=datetime.date(2026, 6, 16),
                meal="lunch",
                quantity=1.0,
                unit="serving",
                kcal=105.0,
                protein_g=1.3,
                carbs_g=27.0,
                fat_g=0.4,
            )
        )
        await session.commit()
        user_id = user.id

    async with AsyncSessionLocal() as session:
        await session.execute(delete(User).where(User.id == user_id))
        await session.commit()

    async with AsyncSessionLocal() as session:
        remaining = await session.execute(
            select(FoodLogEntry).where(FoodLogEntry.user_id == user_id)
        )
        assert remaining.scalars().first() is None


async def test_deleting_food_nulls_entry_but_keeps_snapshot():
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        f = Food(
            source="user",
            name=f"Doomed Food {uuid.uuid4().hex[:6]}",
            kcal_per_100g=200.0,
            protein_g_per_100g=10.0,
            carbs_g_per_100g=20.0,
            fat_g_per_100g=5.0,
        )
        session.add(f)
        await session.commit()
        entry = FoodLogEntry(
            user_id=user.id,
            food_id=f.id,
            date=datetime.date(2026, 6, 16),
            meal="dinner",
            quantity=100.0,
            unit="g",
            kcal=200.0,
            protein_g=10.0,
            carbs_g=20.0,
            fat_g=5.0,
        )
        session.add(entry)
        await session.commit()
        entry_id, food_id = entry.id, f.id

    async with AsyncSessionLocal() as session:
        await session.execute(delete(Food).where(Food.id == food_id))
        await session.commit()

    async with AsyncSessionLocal() as session:
        loaded = await session.get(FoodLogEntry, entry_id)
        assert loaded is not None
        assert loaded.food_id is None  # SET NULL
        assert loaded.kcal == 200.0  # snapshot preserved


async def test_daily_target_unique_per_user_and_date():
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        day = datetime.date(2026, 6, 16)
        session.add(
            DailyTarget(
                user_id=user.id, date=day, kcal=2000, protein_g=150, carbs_g=200, fat_g=60
            )
        )
        await session.commit()
        user_id = user.id

    async with AsyncSessionLocal() as session:
        session.add(
            DailyTarget(
                user_id=user_id,
                date=datetime.date(2026, 6, 16),
                kcal=2100,
                protein_g=155,
                carbs_g=210,
                fat_g=62,
            )
        )
        with pytest.raises(IntegrityError):
            await session.commit()


async def test_user_goal_persists():
    async with AsyncSessionLocal() as session:
        user = await _make_user(session)
        session.add(
            UserGoal(
                user_id=user.id,
                goal_type="cut",
                weight_kg=80.0,
                height_cm=180.0,
                age=30,
                sex="male",
                activity_level="moderate",
                rate_kg_per_week=-0.5,
            )
        )
        await session.commit()
        user_id = user.id

    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(UserGoal).where(UserGoal.user_id == user_id)
        )
        goal = result.scalar_one()
        assert goal.goal_type == "cut"
        assert goal.rate_kg_per_week == -0.5

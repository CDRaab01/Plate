import datetime
import uuid

from sqlalchemy import DateTime, Float, ForeignKey, Integer, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class UserGoal(Base):
    """The user's current goal + the body inputs the targets engine needs (Phase 3).

    A new row is appended when the goal changes; the most recent row is the active goal, so the
    history is preserved for later trend/coach context.
    """

    __tablename__ = "user_goals"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    goal_type: Mapped[str] = mapped_column(String(20))  # maintain | cut | bulk
    weight_kg: Mapped[float] = mapped_column(Float)
    height_cm: Mapped[float] = mapped_column(Float)
    age: Mapped[int] = mapped_column(Integer)
    sex: Mapped[str] = mapped_column(String(10))  # male | female
    # sedentary | light | moderate | active | very_active — maps to a Mifflin factor in Phase 3.
    activity_level: Mapped[str] = mapped_column(String(20))
    # Target rate of weight change in kg/week (e.g. -0.5 for a cut, 0 for maintain).
    rate_kg_per_week: Mapped[float] = mapped_column(Float, server_default="0")

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="goals", lazy="raise")

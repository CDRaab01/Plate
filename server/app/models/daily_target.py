import datetime
import uuid

from sqlalchemy import Date, DateTime, Float, ForeignKey, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class DailyTarget(Base):
    """The computed calorie/macro targets for one user on one date.

    Stored (not recomputed on read) so history is stable even as the goals or training-day
    adjustment logic evolves. One row per (user, date).
    """

    __tablename__ = "daily_targets"
    __table_args__ = (
        UniqueConstraint("user_id", "date", name="uq_daily_targets_user_date"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    date: Mapped[datetime.date] = mapped_column(Date)
    kcal: Mapped[float] = mapped_column(Float)
    protein_g: Mapped[float] = mapped_column(Float)
    carbs_g: Mapped[float] = mapped_column(Float)
    fat_g: Mapped[float] = mapped_column(Float)

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="daily_targets", lazy="raise")

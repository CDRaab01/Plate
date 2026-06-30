import datetime
import uuid

from sqlalchemy import Date, DateTime, Float, ForeignKey, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class BodyMetric(Base):
    """A single bodyweight log entry (a weigh-in) for a date.

    Weight is stored in **kg** (canonical) regardless of the user's display preference — the
    targets engine works in kg, and a single storage unit avoids round-trip drift. Conversion to/
    from the user's lb/kg preference happens at the API edge (see :mod:`app.nutrition.units`).

    The series powers the weight-trend / on-pace feedback and feeds the targets engine: the most
    recent weigh-in on or before a day is preferred over the goal's static ``weight_kg`` so a cut's
    deficit tracks current weight instead of going stale.
    """

    __tablename__ = "body_metrics"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    weight: Mapped[float] = mapped_column(Float)  # kilograms (canonical)
    bodyfat: Mapped[float | None] = mapped_column(Float, nullable=True)  # percent

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="metrics", lazy="raise")

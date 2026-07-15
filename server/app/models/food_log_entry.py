import datetime
import uuid

from sqlalchemy import Date, DateTime, Float, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class FoodLogEntry(Base):
    """A single logged food, bucketed into a meal for a given date.

    The macro fields are a **denormalized snapshot** computed at log time, so later edits to the
    source :class:`Food` never rewrite history. ``food_id`` is nullable / SET NULL on delete for
    the same reason — the entry keeps its numbers even if the source food is removed.
    """

    __tablename__ = "food_log_entries"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    food_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("foods.id", ondelete="SET NULL"), nullable=True
    )
    # Label for entries with no source food (quick-add direct-macro entries); when ``food_id`` is
    # set the displayed name comes from the source food instead.
    name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    meal: Mapped[str] = mapped_column(String(20))  # breakfast | lunch | dinner | snack
    quantity: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    # Correlation tag for entries created by a sister app (Cookbook) on behalf of the user, so it
    # can adjust (delete-by-ref + re-log) or retract (delete-by-ref) that meal. NULL for entries
    # Plate logs itself.
    source_ref: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)

    # Denormalized macro snapshot
    kcal: Mapped[float] = mapped_column(Float)
    protein_g: Mapped[float] = mapped_column(Float)
    carbs_g: Mapped[float] = mapped_column(Float)
    fat_g: Mapped[float] = mapped_column(Float)
    fiber_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    sugar_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    sat_fat_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    cholesterol_mg: Mapped[float | None] = mapped_column(Float, nullable=True)
    sodium_mg: Mapped[float | None] = mapped_column(Float, nullable=True)

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="log_entries", lazy="raise")
    food = relationship("Food", lazy="raise")

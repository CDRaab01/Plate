import datetime
import uuid

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Food(Base):
    """A canonical food/nutrition record.

    Source-tagged (``usda`` | ``off`` | ``user``) and cached locally on first fetch so search
    serves from the DB after. Nutrition is stored **both per 100g and per serving** so the
    client can log by either basis without recomputing from the source API.
    """

    __tablename__ = "foods"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    source: Mapped[str] = mapped_column(String(20))  # usda | off | user
    source_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    name: Mapped[str] = mapped_column(String(255), index=True)
    brand: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Barcode is "unique-ish": indexed for lookup but dedup is handled in the app layer, since
    # OFF occasionally returns the same code for regional variants.
    barcode: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    serving_size: Mapped[float | None] = mapped_column(Float, nullable=True)
    serving_unit: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # Human-readable serving text from the source ("2 cookies (30 g)") for result rows.
    serving_label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # True when the source record was missing some primary macros and zeros were imputed —
    # the client badges these and ranking demotes them below complete foods.
    macros_incomplete: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    # Owner of a user-created food ("My foods"). NULL = legacy/shared, visible to everyone.
    created_by: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    # Lazy USDA portion enrichment marker: NULL = FDC detail never checked for this food.
    portions_fetched_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # Nutrition per 100g (primary basis; always populated)
    kcal_per_100g: Mapped[float] = mapped_column(Float)
    protein_g_per_100g: Mapped[float] = mapped_column(Float)
    carbs_g_per_100g: Mapped[float] = mapped_column(Float)
    fat_g_per_100g: Mapped[float] = mapped_column(Float)
    fiber_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    sugar_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    sat_fat_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    cholesterol_mg_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    sodium_mg_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)

    # Nutrition per serving (optional; present when the source defines a serving)
    kcal_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    protein_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    carbs_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    fat_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    fiber_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    sugar_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    sat_fat_g_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    cholesterol_mg_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)
    sodium_mg_per_serving: Mapped[float | None] = mapped_column(Float, nullable=True)

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    # lazy="raise" keeps search queries from paying a per-row portions round-trip; the detail
    # path (`get_food_detail`) eager-loads explicitly with selectinload.
    portions = relationship(
        "FoodPortion",
        back_populates="food",
        order_by="FoodPortion.sort_order",
        cascade="all, delete-orphan",
        lazy="raise",
    )

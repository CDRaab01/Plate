import uuid

from sqlalchemy import Float, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class FoodPortion(Base):
    """A named household measure for a food ("1 cup, sliced" = 240 g).

    Sourced from USDA ``foodPortions`` (Foundation/SR Legacy detail + bulk exports), Open Food
    Facts serving labels, or user input. ``gram_weight`` is the grams in **one** of this
    portion; logging N of them scales from the food's per-100g basis, so the macro math stays
    in ``app/nutrition/``. The food's own "1 serving" (``serving_size``/``*_per_serving``) is
    not duplicated here — the picker synthesizes it.
    """

    __tablename__ = "food_portions"
    __table_args__ = (
        UniqueConstraint("food_id", "description", name="uq_food_portions_food_desc"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    food_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("foods.id", ondelete="CASCADE"), index=True
    )
    description: Mapped[str] = mapped_column(String(64))
    gram_weight: Mapped[float] = mapped_column(Float)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    source: Mapped[str] = mapped_column(String(20))  # usda | off | user

    food = relationship("Food", back_populates="portions", lazy="raise")

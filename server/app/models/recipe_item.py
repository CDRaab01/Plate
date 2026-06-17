import uuid

from sqlalchemy import Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class RecipeItem(Base):
    """One food in a recipe, with the quantity/unit it contributes (CLAUDE.md §4).

    ``food_id`` is SET NULL on delete (like :class:`~app.models.food_log_entry.FoodLogEntry`) so a
    removed source food doesn't cascade away the recipe; such an orphaned item is skipped when the
    recipe is totalled or logged.
    """

    __tablename__ = "recipe_items"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    recipe_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )
    food_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("foods.id", ondelete="SET NULL"), nullable=True
    )
    quantity: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    order: Mapped[int] = mapped_column(Integer, default=0)

    recipe = relationship("Recipe", back_populates="items", lazy="raise")
    food = relationship("Food", lazy="selectin")

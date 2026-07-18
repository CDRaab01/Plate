import uuid

from sqlalchemy import Boolean, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class RestaurantComponent(Base):
    """One checkbox row on a restaurant's build-your-own menu ("Barbacoa" under "Protein").

    ``name`` is the menu's display label and ``food_id`` the nutrition source behind it — a
    trusted-search food (USDA generic ⇒ estimate) or a minted official food (the restaurant's
    published numbers). ``food_id`` is SET NULL on delete (the recipe_items precedent) so a
    removed food orphans the row instead of cascading it away; the label survives and the row is
    skipped when logging until re-linked.
    """

    __tablename__ = "restaurant_components"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    restaurant_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("restaurants.id", ondelete="CASCADE"), index=True
    )
    category: Mapped[str] = mapped_column(String(64))
    name: Mapped[str] = mapped_column(String(255))
    food_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("foods.id", ondelete="SET NULL"), nullable=True
    )
    quantity: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    order: Mapped[int] = mapped_column(Integer, default=0)
    default_checked: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")

    restaurant = relationship("Restaurant", back_populates="components", lazy="raise")
    food = relationship("Food", lazy="selectin")

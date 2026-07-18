import datetime
import uuid

from sqlalchemy import Boolean, DateTime, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Restaurant(Base):
    """A chain/restaurant checkbox template: categorized components the user ticks to log a meal.

    Like :class:`~app.models.recipe.Recipe` this is a **live template**, not history — logging
    snapshots the ticked components into ``food_log_entries``. Unlike recipes, restaurants default
    to ``shared``: a chain's menu is objectively shared data, so every account on this server can
    see and log from a shared restaurant (entries land in the logger's own diary); only the owner
    edits or deletes it.
    """

    __tablename__ = "restaurants"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    menu_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    shared: Mapped[bool] = mapped_column(Boolean, default=True, server_default="true")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    components = relationship(
        "RestaurantComponent",
        back_populates="restaurant",
        cascade="all, delete-orphan",
        order_by="RestaurantComponent.order",
        lazy="selectin",
    )

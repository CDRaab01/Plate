import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Recipe(Base):
    """A user-saved meal: a named, ordered set of foods (CLAUDE.md §4).

    A recipe is a **live template**, not history — its totals are computed from the current source
    foods at read time. Logging a recipe snapshots each item into ``food_log_entries`` (see
    :func:`app.services.recipe_service.log_recipe`), so the logged macros are immutable history even
    if the recipe or its foods later change. Mirrors Spotter's ``WorkoutProgram``/``ProgramDay``
    parent-with-ordered-children pattern.
    """

    __tablename__ = "recipes"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    items = relationship(
        "RecipeItem",
        back_populates="recipe",
        cascade="all, delete-orphan",
        order_by="RecipeItem.order",
        lazy="selectin",
    )

"""recipes, recipe_items, and food_log_entries.name (Phase 8)

Revision ID: 0002
Revises: 0001
Create Date: 2026-06-16

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Label for quick-add entries (no source food); existing rows keep NULL and read their name
    # from the source food as before.
    op.add_column("food_log_entries", sa.Column("name", sa.String(255), nullable=True))

    op.create_table(
        "recipes",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_recipes_user_id", "recipes", ["user_id"])

    op.create_table(
        "recipe_items",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "recipe_id",
            UUID(as_uuid=True),
            sa.ForeignKey("recipes.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "food_id",
            UUID(as_uuid=True),
            sa.ForeignKey("foods.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("quantity", sa.Float(), nullable=False),
        sa.Column("unit", sa.String(32), nullable=False),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
    )
    op.create_index("ix_recipe_items_recipe_id", "recipe_items", ["recipe_id"])


def downgrade() -> None:
    op.drop_index("ix_recipe_items_recipe_id", "recipe_items")
    op.drop_table("recipe_items")
    op.drop_index("ix_recipes_user_id", "recipes")
    op.drop_table("recipes")
    op.drop_column("food_log_entries", "name")

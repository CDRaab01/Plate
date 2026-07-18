"""restaurants and restaurant_components (chain meal logging)

Revision ID: 0006
Revises: 0005
Create Date: 2026-07-18

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "restaurants",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        # Provenance + re-parse convenience only; never re-fetched implicitly.
        sa.Column("menu_url", sa.Text(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        # A chain's menu is objectively shared data: shared restaurants are visible/loggable by
        # every account on this server (a two-person household deploy), editable only by the owner.
        sa.Column("shared", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_restaurants_user_id", "restaurants", ["user_id"])

    op.create_table(
        "restaurant_components",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "restaurant_id",
            UUID(as_uuid=True),
            sa.ForeignKey("restaurants.id", ondelete="CASCADE"),
            nullable=False,
        ),
        # Menu section the checkbox row lives under ("Protein", "Rice", "Toppings", ...).
        sa.Column("category", sa.String(64), nullable=False),
        # Menu display name ("Barbacoa") — survives deletion of the linked food, unlike
        # recipe_items which lose their label when the food goes.
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column(
            "food_id",
            UUID(as_uuid=True),
            sa.ForeignKey("foods.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("quantity", sa.Float(), nullable=False),
        sa.Column("unit", sa.String(32), nullable=False),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("default_checked", sa.Boolean(), nullable=False, server_default=sa.text("false")),
    )
    op.create_index(
        "ix_restaurant_components_restaurant_id", "restaurant_components", ["restaurant_id"]
    )


def downgrade() -> None:
    op.drop_index("ix_restaurant_components_restaurant_id", "restaurant_components")
    op.drop_table("restaurant_components")
    op.drop_index("ix_restaurants_user_id", "restaurants")
    op.drop_table("restaurants")

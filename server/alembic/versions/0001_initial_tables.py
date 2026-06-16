"""initial tables

Revision ID: 0001
Revises:
Create Date: 2026-06-16

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("hashed_password", sa.String(255), nullable=False),
        sa.Column("settings", sa.Text(), nullable=True),
        sa.Column("reset_token", sa.String(64), nullable=True),
        sa.Column("reset_token_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "foods",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("source", sa.String(20), nullable=False),
        sa.Column("source_id", sa.String(255), nullable=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("brand", sa.String(255), nullable=True),
        sa.Column("barcode", sa.String(64), nullable=True),
        sa.Column("serving_size", sa.Float(), nullable=True),
        sa.Column("serving_unit", sa.String(32), nullable=True),
        sa.Column("kcal_per_100g", sa.Float(), nullable=False),
        sa.Column("protein_g_per_100g", sa.Float(), nullable=False),
        sa.Column("carbs_g_per_100g", sa.Float(), nullable=False),
        sa.Column("fat_g_per_100g", sa.Float(), nullable=False),
        sa.Column("fiber_g_per_100g", sa.Float(), nullable=True),
        sa.Column("sugar_g_per_100g", sa.Float(), nullable=True),
        sa.Column("sat_fat_g_per_100g", sa.Float(), nullable=True),
        sa.Column("cholesterol_mg_per_100g", sa.Float(), nullable=True),
        sa.Column("sodium_mg_per_100g", sa.Float(), nullable=True),
        sa.Column("kcal_per_serving", sa.Float(), nullable=True),
        sa.Column("protein_g_per_serving", sa.Float(), nullable=True),
        sa.Column("carbs_g_per_serving", sa.Float(), nullable=True),
        sa.Column("fat_g_per_serving", sa.Float(), nullable=True),
        sa.Column("fiber_g_per_serving", sa.Float(), nullable=True),
        sa.Column("sugar_g_per_serving", sa.Float(), nullable=True),
        sa.Column("sat_fat_g_per_serving", sa.Float(), nullable=True),
        sa.Column("cholesterol_mg_per_serving", sa.Float(), nullable=True),
        sa.Column("sodium_mg_per_serving", sa.Float(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_foods_name", "foods", ["name"])
    op.create_index("ix_foods_barcode", "foods", ["barcode"])

    op.create_table(
        "food_log_entries",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "food_id",
            UUID(as_uuid=True),
            sa.ForeignKey("foods.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("meal", sa.String(20), nullable=False),
        sa.Column("quantity", sa.Float(), nullable=False),
        sa.Column("unit", sa.String(32), nullable=False),
        sa.Column("kcal", sa.Float(), nullable=False),
        sa.Column("protein_g", sa.Float(), nullable=False),
        sa.Column("carbs_g", sa.Float(), nullable=False),
        sa.Column("fat_g", sa.Float(), nullable=False),
        sa.Column("fiber_g", sa.Float(), nullable=True),
        sa.Column("sugar_g", sa.Float(), nullable=True),
        sa.Column("sat_fat_g", sa.Float(), nullable=True),
        sa.Column("cholesterol_mg", sa.Float(), nullable=True),
        sa.Column("sodium_mg", sa.Float(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_food_log_entries_user_id", "food_log_entries", ["user_id"])
    op.create_index("ix_food_log_entries_date", "food_log_entries", ["date"])

    op.create_table(
        "user_goals",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("goal_type", sa.String(20), nullable=False),
        sa.Column("weight_kg", sa.Float(), nullable=False),
        sa.Column("height_cm", sa.Float(), nullable=False),
        sa.Column("age", sa.Integer(), nullable=False),
        sa.Column("sex", sa.String(10), nullable=False),
        sa.Column("activity_level", sa.String(20), nullable=False),
        sa.Column("rate_kg_per_week", sa.Float(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_user_goals_user_id", "user_goals", ["user_id"])

    op.create_table(
        "daily_targets",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("kcal", sa.Float(), nullable=False),
        sa.Column("protein_g", sa.Float(), nullable=False),
        sa.Column("carbs_g", sa.Float(), nullable=False),
        sa.Column("fat_g", sa.Float(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.UniqueConstraint("user_id", "date", name="uq_daily_targets_user_date"),
    )
    op.create_index("ix_daily_targets_user_id", "daily_targets", ["user_id"])


def downgrade() -> None:
    op.drop_table("daily_targets")
    op.drop_table("user_goals")
    op.drop_table("food_log_entries")
    op.drop_index("ix_foods_barcode", "foods")
    op.drop_index("ix_foods_name", "foods")
    op.drop_table("foods")
    op.drop_index("ix_users_email", "users")
    op.drop_table("users")

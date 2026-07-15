"""food_log source_ref (cross-app correlation for confirm/adjust/un-eat)

Revision ID: 0004
Revises: 0003
Create Date: 2026-07-14

Adds a nullable ``source_ref`` to ``food_log_entries`` so a sister app (Cookbook) can correlate
the diary entries it created for one confirmed meal. Cookbook passes a stable ref on
``POST /cross-app/log-recipe``; changing a portion is delete-by-ref + re-log, and un-checking a
meal is delete-by-ref. Null for every entry Plate creates itself — this only tags cross-app logs.
"""
from alembic import op
import sqlalchemy as sa

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "food_log_entries",
        sa.Column("source_ref", sa.String(length=128), nullable=True),
    )
    # Deletes/lookups are always scoped (user_id, source_ref); index the ref for that path.
    op.create_index(
        "ix_food_log_entries_source_ref", "food_log_entries", ["source_ref"]
    )


def downgrade() -> None:
    op.drop_index("ix_food_log_entries_source_ref", "food_log_entries")
    op.drop_column("food_log_entries", "source_ref")

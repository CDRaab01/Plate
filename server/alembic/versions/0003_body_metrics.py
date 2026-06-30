"""body_metrics (bodyweight logging / weight trend)

Revision ID: 0003
Revises: 0002
Create Date: 2026-06-30

Adds the bodyweight log that powers the weight-trend / on-pace feedback and the adaptive-targets
weight lookup. Weight is stored in kg (canonical); display conversion to lb happens at the API edge.

Note: the ``daily_targets`` table (from 0001) remains intentionally unused — targets are computed
on read so they stay adaptive to the latest weigh-in; a per-day snapshot would defeat that.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "body_metrics",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("weight", sa.Float(), nullable=False),  # kilograms (canonical)
        sa.Column("bodyfat", sa.Float(), nullable=True),  # percent
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )
    op.create_index("ix_body_metrics_user_id", "body_metrics", ["user_id"])
    op.create_index("ix_body_metrics_date", "body_metrics", ["date"])


def downgrade() -> None:
    op.drop_index("ix_body_metrics_date", "body_metrics")
    op.drop_index("ix_body_metrics_user_id", "body_metrics")
    op.drop_table("body_metrics")

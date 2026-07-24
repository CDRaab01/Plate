"""food portions, fuzzy search, per-query external throttle (food-search restructure)

Revision ID: 0007
Revises: 0006
Create Date: 2026-07-24

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Fuzzy local search: trigram similarity on food names. postgres:16 ships pg_trgm in
    # contrib and the compose user is superuser, so this succeeds on our deploys; a managed
    # Postgres would need the extension pre-created by an admin.
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
    op.create_index(
        "ix_foods_name_trgm",
        "foods",
        ["name"],
        postgresql_using="gin",
        postgresql_ops={"name": "gin_trgm_ops"},
    )

    # Named household portions ("1 cup, sliced" = 240 g) — USDA foodPortions / OFF serving
    # labels. A food's plain "1 serving" stays on the foods row (serving_size/_per_serving);
    # these are the *additional* measures a picker can offer.
    op.create_table(
        "food_portions",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "food_id",
            UUID(as_uuid=True),
            sa.ForeignKey("foods.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("description", sa.String(64), nullable=False),
        sa.Column("gram_weight", sa.Float(), nullable=False),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("source", sa.String(20), nullable=False),
        sa.UniqueConstraint("food_id", "description", name="uq_food_portions_food_desc"),
    )
    op.create_index("ix_food_portions_food_id", "food_portions", ["food_id"])

    # Human-readable serving text from the source ("2 cookies (30 g)") for richer result rows.
    op.add_column("foods", sa.Column("serving_label", sa.String(64), nullable=True))
    # External records missing some primary macros are now kept (zeros imputed) and flagged
    # instead of silently dropped; the client badges them and ranking demotes them.
    op.add_column(
        "foods",
        sa.Column(
            "macros_incomplete", sa.Boolean(), nullable=False, server_default=sa.text("false")
        ),
    )
    # Owner of a user-created food ("My foods" filter). Legacy rows stay NULL = visible to all.
    op.add_column(
        "foods",
        sa.Column(
            "created_by",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    # Lazy USDA portion enrichment marker: NULL = FDC detail never checked for this food.
    op.add_column(
        "foods", sa.Column("portions_fetched_at", sa.DateTime(timezone=True), nullable=True)
    )

    # Gram weight of the named portion an entry was logged with, snapshotted so quantity edits
    # keep re-scaling correctly even if the portion row later changes or disappears.
    op.add_column(
        "food_log_entries", sa.Column("portion_gram_weight", sa.Float(), nullable=True)
    )

    # Per-query external-fetch throttle: replaces the old "cache has a full page → never hit
    # the network again" shortcut with a TTL, so external sources keep supplementing.
    op.create_table(
        "search_queries",
        sa.Column("normalized_query", sa.String(255), primary_key=True),
        sa.Column("last_fetched_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("search_queries")
    op.drop_column("food_log_entries", "portion_gram_weight")
    op.drop_column("foods", "portions_fetched_at")
    op.drop_column("foods", "created_by")
    op.drop_column("foods", "macros_incomplete")
    op.drop_column("foods", "serving_label")
    op.drop_index("ix_food_portions_food_id", "food_portions")
    op.drop_table("food_portions")
    # Leave the pg_trgm extension installed — it's a shared database object other code may use.
    op.drop_index("ix_foods_name_trgm", "foods")

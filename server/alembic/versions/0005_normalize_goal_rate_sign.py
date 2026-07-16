"""normalize existing user_goals rate sign to match goal_type

Revision ID: 0005
Revises: 0004
Create Date: 2026-07-15

Heals rows written before ``GoalUpsert`` made ``goal_type`` authoritative for the direction of the
weight-change rate. The old goal screen took the rate as a signed number ("Negative to cut"), so a
user who selected **Cut** but entered a natural *positive* rate stored a surplus — and the targets
engine, which reads ``rate_kg_per_week`` straight off the row, handed back calories ABOVE
maintenance for what was meant to be a cut (and the mirror for a bulk). The schema fix only corrects
*new* writes; this back-fills the invariant onto existing rows so already-saved goals stop serving
wrong targets without the user having to re-save:

    cut  -> rate = -abs(rate)   (a deficit)
    bulk -> rate = +abs(rate)   (a surplus)
    else -> rate = 0            (maintain has no rate)

Idempotent: rows already consistent are unchanged. History rows are corrected too — harmless, since
only the latest row per user is the active goal.
"""

from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # A cut is always a deficit; a bulk always a surplus; maintain (or anything else) has no rate.
    op.execute(
        "UPDATE user_goals SET rate_kg_per_week = -abs(rate_kg_per_week) WHERE goal_type = 'cut'"
    )
    op.execute(
        "UPDATE user_goals SET rate_kg_per_week = abs(rate_kg_per_week) WHERE goal_type = 'bulk'"
    )
    op.execute("UPDATE user_goals SET rate_kg_per_week = 0 WHERE goal_type NOT IN ('cut', 'bulk')")


def downgrade() -> None:
    # The pre-fix sign was free-form user input; there is nothing faithful to restore.
    pass

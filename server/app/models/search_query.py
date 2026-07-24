import datetime

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class SearchQuery(Base):
    """Per-query throttle for external food-source fan-out.

    One row per normalized search query, stamped when USDA/OFF were last asked about it.
    ``search_foods`` skips the network while the stamp is fresher than
    ``settings.external_refetch_ttl_hours`` — repeated queries stay local-only, but the cache
    can no longer *permanently* shadow richer external data the way the old "full local page →
    never fetch again" shortcut did.
    """

    __tablename__ = "search_queries"

    normalized_query: Mapped[str] = mapped_column(String(255), primary_key=True)
    last_fetched_at: Mapped[datetime.datetime] = mapped_column(DateTime(timezone=True))

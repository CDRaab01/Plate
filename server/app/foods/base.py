"""The :class:`FoodSource` abstraction.

A source knows how to turn a free-text query (and, for OFF, a barcode) into normalized foods.
Concrete sources (:mod:`app.foods.usda`, :mod:`app.foods.off`) wrap an external HTTP API; tests
substitute simple in-memory fakes that satisfy the same interface.
"""
from abc import ABC, abstractmethod

from app.foods.normalize import NormalizedFood


class FoodSource(ABC):
    """Common contract for a provider of normalized food records."""

    #: ``usda`` | ``off`` | ``user`` — stamped onto cached rows so their origin is traceable.
    source_tag: str

    @abstractmethod
    async def search(self, query: str, *, limit: int) -> list[NormalizedFood]:
        """Return up to ``limit`` foods matching ``query`` (best-effort; may be empty)."""
        raise NotImplementedError

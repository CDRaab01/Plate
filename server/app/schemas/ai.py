"""AI coach chat request/response models (CLAUDE.md §6).

Mirrors Spotter's chat contract: a list of role/content turns in, a single assistant reply out.
The macro context the coach reasons over (remaining kcal/macros for the day + goal) is derived
**server-side** from the user's log and goal — never trusted from the client — so it isn't part of
the request body. Phase 5 keeps the response to prose only; structured food suggestions are a later
enhancement.
"""

from pydantic import BaseModel, field_validator

ROLES = ("user", "assistant", "system")


class ChatMessage(BaseModel):
    """One turn of the conversation. ``system`` turns from the client are ignored when building the
    prompt — the system prompt is set server-side — but are accepted so a full transcript round-trips.
    """

    role: str
    content: str

    @field_validator("role")
    @classmethod
    def role_valid(cls, v: str) -> str:
        key = v.strip().lower()
        if key not in ROLES:
            raise ValueError(f"role must be one of {ROLES}")
        return key


class ChatRequest(BaseModel):
    """The full conversation so far. The last user turn is the new message; earlier turns are
    history. At least one message is required."""

    messages: list[ChatMessage]

    @field_validator("messages")
    @classmethod
    def non_empty(cls, v: list[ChatMessage]) -> list[ChatMessage]:
        if not v:
            raise ValueError("messages must not be empty")
        return v


class ChatResponse(BaseModel):
    reply: str

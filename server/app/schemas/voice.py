"""Voice-logging request model (CLAUDE.md §6).

Speech→text happens **on-device** (Android's `SpeechRecognizer`) — no audio ever leaves the phone.
The client POSTs only the recognized *text*; the server runs a structured LM Studio parse, resolves
each spoken food against the food search, and returns an editable draft (the shared
:class:`~app.schemas.photo.PhotoEstimateResponse`) the user confirms. Nothing is auto-logged.
"""

from pydantic import BaseModel, Field


class VoiceParseRequest(BaseModel):
    """A short recognized utterance describing what was eaten, e.g. "two eggs and a banana"."""

    # Cap the length: a food utterance is short, and a long paste is almost certainly misuse / an
    # injection attempt that would waste the local model's context.
    text: str = Field(min_length=1, max_length=500)

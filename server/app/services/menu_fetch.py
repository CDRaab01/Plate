"""Fetch a restaurant's menu URL and extract its text (HTML or PDF) for the LM parse.

Plain network/IO — deliberately not under ``services/ai/``. The fetch runs server-side against a
user-supplied URL, so it is hardened:

* **http(s) only**, and by default an **SSRF guard** rejects hosts resolving to private /
  loopback / link-local / reserved addresses (``menu_fetch_block_private_ips`` opts a homelab
  deploy out). Redirects are followed manually (max 3 hops) with the guard re-run per hop —
  httpx's auto-redirect would bypass it.
* **Streaming size cap** (``menu_fetch_max_bytes``) and a request timeout.
* Content handling by declared type (URL suffix as fallback): ``application/pdf`` → pypdf text
  extraction, ``text/html`` → a stdlib tag-stripper (scripts/styles dropped), else UTF-8 decode.

An image-only PDF (designed menus often rasterize — Salsa Grille's does) yields no text: that's
a 422 pointing the user at the manual builder, not a parse of garbage.
"""

import asyncio
import ipaddress
from html.parser import HTMLParser
from io import BytesIO
from urllib.parse import urljoin, urlsplit

import httpx
from fastapi import HTTPException, status
from pypdf import PdfReader

from app.config import settings

_MAX_REDIRECTS = 3

_BAD_URL = HTTPException(
    status_code=status.HTTP_400_BAD_REQUEST,
    detail="Menu URL must be a public http(s) address.",
)
_TOO_LARGE = HTTPException(
    status_code=status.HTTP_400_BAD_REQUEST,
    detail="Menu is too large to read.",
)
_UNREACHABLE = HTTPException(
    status_code=status.HTTP_400_BAD_REQUEST,
    detail="Couldn't reach that menu URL.",
)
_NO_TEXT = HTTPException(
    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
    detail=(
        "Couldn't read a menu from that link — the page may load its menu with JavaScript "
        "(many big chains like Starbucks do) or be an image-only PDF. Paste the nutrition text "
        "instead, use a bundled chain preset, or build the restaurant by hand."
    ),
)


class _TextExtractor(HTMLParser):
    """Collect visible text, dropping <script>/<style> subtrees."""

    _SKIP = {"script", "style", "noscript"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._chunks: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in self._SKIP:
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in self._SKIP and self._skip_depth:
            self._skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self._skip_depth and data.strip():
            self._chunks.append(data.strip())

    def text(self) -> str:
        return "\n".join(self._chunks)


def _html_to_text(html: str) -> str:
    extractor = _TextExtractor()
    extractor.feed(html)
    return extractor.text()


def _pdf_to_text(data: bytes) -> str:
    try:
        reader = PdfReader(BytesIO(data))
        return "\n".join((page.extract_text() or "") for page in reader.pages)
    except Exception:  # noqa: BLE001 — any unparseable PDF degrades to "no text" (422 below)
        return ""


async def _guard_host(url: str) -> None:
    """Reject non-http(s) schemes and (unless opted out) hosts resolving to non-public ranges."""
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise _BAD_URL
    if not settings.menu_fetch_block_private_ips:
        return
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(parts.hostname, parts.port or 80)
    except OSError as exc:
        raise _UNREACHABLE from exc
    for info in infos:
        address = ipaddress.ip_address(info[4][0])
        if (
            address.is_private
            or address.is_loopback
            or address.is_link_local
            or address.is_reserved
            or address.is_unspecified
        ):
            raise _BAD_URL


async def _read_capped(resp: httpx.Response) -> bytes:
    declared = resp.headers.get("Content-Length")
    if declared and declared.isdigit() and int(declared) > settings.menu_fetch_max_bytes:
        raise _TOO_LARGE
    body = bytearray()
    async for chunk in resp.aiter_bytes():
        body.extend(chunk)
        if len(body) > settings.menu_fetch_max_bytes:
            raise _TOO_LARGE
    return bytes(body)


def _extract_text(url: str, content_type: str, body: bytes) -> str:
    kind = content_type.split(";")[0].strip().lower()
    is_pdf = kind == "application/pdf" or (not kind and url.lower().endswith(".pdf"))
    if is_pdf or body[:5] == b"%PDF-":
        return _pdf_to_text(body)
    text = body.decode("utf-8", errors="replace")
    if kind == "text/html" or (not kind and "<html" in text[:1024].lower()):
        return _html_to_text(text)
    return text


async def fetch_menu_text(url: str, *, client: httpx.AsyncClient | None = None) -> str:
    """Fetch ``url`` (following up to 3 guarded redirects) and return its extracted text.

    Raises 400 for a bad/blocked/oversized URL, 422 when no text could be extracted.
    """
    own_client = client is None
    if own_client:
        client = httpx.AsyncClient(timeout=settings.menu_fetch_timeout_seconds)
    try:
        current = url
        for _ in range(_MAX_REDIRECTS + 1):
            await _guard_host(current)
            try:
                async with client.stream(
                    "GET", current, follow_redirects=False, headers={"User-Agent": "Plate/1.0"}
                ) as resp:
                    if resp.status_code in (301, 302, 303, 307, 308):
                        location = resp.headers.get("Location")
                        if not location:
                            raise _UNREACHABLE
                        current = urljoin(current, location)
                        continue
                    if resp.status_code >= 400:
                        raise _UNREACHABLE
                    body = await _read_capped(resp)
                    content_type = resp.headers.get("Content-Type", "")
            except httpx.HTTPError as exc:
                raise _UNREACHABLE from exc
            text = _extract_text(current, content_type, body).strip()
            if not text:
                raise _NO_TEXT
            return text
        raise _UNREACHABLE  # redirect loop
    finally:
        if own_client:
            await client.aclose()

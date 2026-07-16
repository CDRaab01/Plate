import uuid
from typing import Annotated

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.schemas.food import FoodCreate, FoodOut
from app.schemas.photo import PhotoEstimateResponse
from app.security import CurrentUser
from app.services.ai.vision import estimate_label, estimate_photo
from app.services.food_service import (
    create_custom_food,
    get_food,
    lookup_barcode,
    search_foods,
)

router = APIRouter(prefix="/foods", tags=["foods"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/search", response_model=list[FoodOut])
async def search(
    current_user: CurrentUser,
    db: DbSession,
    q: Annotated[str, Query(min_length=1, description="Search text")],
):
    """Local-cache-first food search, ranked with the user's recently-logged foods first.
    External sources are hit only on a cache miss."""
    return await search_foods(db, q, user_id=current_user.id)


@router.get("/barcode/{code}", response_model=FoodOut)
async def read_barcode(
    code: str,
    current_user: CurrentUser,
    db: DbSession,
):
    """Scan path: resolve a barcode via local cache → Open Food Facts, caching on first fetch."""
    food = await lookup_barcode(db, code)
    if food is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No product found for this barcode",
        )
    return food


@router.post("", response_model=FoodOut, status_code=status.HTTP_201_CREATED)
async def create_food(
    req: FoodCreate,
    current_user: CurrentUser,
    db: DbSession,
):
    """Create a user-defined custom food for items not found in USDA/OFF."""
    return await create_custom_food(db, req.model_dump())


async def _read_validated_image(image: UploadFile) -> tuple[bytes, str]:
    """Validate an uploaded image (type/non-empty/size) and return its bytes + content-type.

    Shared by the meal-photo and nutrition-label routes so both enforce the same upload rules.
    """
    content_type = (image.content_type or "").lower()
    if not content_type.startswith("image/"):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Upload an image (JPEG or PNG).",
        )

    data = await image.read()
    if not data:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="The image is empty.")
    if len(data) > settings.photo_max_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="That image is too large — try a smaller photo.",
        )
    return data, content_type


@router.post("/photo", response_model=PhotoEstimateResponse)
@limiter.limit("10/minute")
async def estimate_from_photo(
    request: Request,
    image: UploadFile,
    current_user: CurrentUser,
):
    """Photo logging (Phase 6, CLAUDE.md §6): estimate the foods + macros in a meal photo.

    The vision model returns an **editable draft** — this endpoint never logs anything itself. The
    client shows the estimate for the user to confirm/edit, then logs via the normal food + log
    endpoints. Rate-limited because each call hits the model.
    """
    data, content_type = await _read_validated_image(image)
    return await estimate_photo(data, content_type)


@router.post("/label", response_model=PhotoEstimateResponse)
@limiter.limit("10/minute")
async def estimate_from_label(
    request: Request,
    image: UploadFile,
    current_user: CurrentUser,
):
    """Nutrition-label scan (CLAUDE.md §6): read a Nutrition Facts panel into an editable draft.

    Higher-accuracy sibling of ``/photo`` — a label states the macros exactly, so the model
    transcribes one food (one serving) rather than estimating a whole meal. Reuses the same
    editable-draft response and the same never-auto-committed guarantee; the client confirms/edits
    before logging. Rate-limited because each call hits the model.
    """
    data, content_type = await _read_validated_image(image)
    return await estimate_label(data, content_type)


@router.get("/{food_id}", response_model=FoodOut)
async def read_food(
    food_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
):
    food = await get_food(db, food_id)
    if food is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Food not found")
    return food

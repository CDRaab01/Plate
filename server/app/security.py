from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


def create_access_token(subject: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(minutes=settings.access_token_expire_minutes)
    return jwt.encode(
        {"sub": subject, "exp": expire, "type": "access"},
        settings.secret_key,
        algorithm=settings.algorithm,
    )


def create_refresh_token(subject: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(days=settings.refresh_token_expire_days)
    return jwt.encode(
        {"sub": subject, "exp": expire, "type": "refresh"},
        settings.secret_key,
        algorithm=settings.algorithm,
    )


async def get_current_user(
    token: Annotated[str, Depends(oauth2_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    from app.models.user import User

    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, settings.secret_key, algorithms=[settings.algorithm])
        if payload.get("type") != "access":
            raise credentials_exception
        user_id: str | None = payload.get("sub")
        if user_id is None:
            raise credentials_exception
    except JWTError:
        raise credentials_exception

    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise credentials_exception
    return user


CurrentUser = Annotated[object, Depends(get_current_user)]


def _verify_legacy_cross_app_token(token: str) -> str | None:
    """Email from a legacy HS256 cross-app token (signed with ``cross_app_secret``), or None.

    The pre-dragonfly-id path (ROADMAP T2 #5 retires it): unset secret ⇒ disabled ⇒ None; a bad
    signature or wrong ``type`` ⇒ None. Kept during the dual-accept transition.
    """
    if not settings.cross_app_secret:
        return None
    try:
        payload = jwt.decode(token, settings.cross_app_secret, algorithms=[settings.algorithm])
    except JWTError:
        return None
    if payload.get("type") != "cross_app":
        return None
    email = payload.get("email")
    return email.strip().lower() if isinstance(email, str) and email.strip() else None


async def get_cross_app_user(
    token: Annotated[str, Depends(oauth2_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Resolve the Plate user from a sister-app (Cookbook) cross-app token.

    Dual-accept during the ROADMAP T2 #5 transition: first the new RS256 dragonfly-id service
    token (``aud="cross-app"``, validated against the JWKS Plate already trusts for SSO), then the
    legacy HS256 ``cross_app_secret`` token. Both carry the user's email — the only stable identity
    across the apps' independent user tables — and neither is a normal Plate session token, so a
    user's own access/refresh token can't reach this surface.
    """
    from app.models.user import User
    from app.services.suite_auth import verify_cross_app_token

    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    email = await verify_cross_app_token(token) or _verify_legacy_cross_app_token(token)
    if not email:
        raise credentials_exception

    result = await db.execute(select(User).where(User.email == email))
    user = result.scalar_one_or_none()
    if user is None:
        raise credentials_exception
    return user


CrossAppUser = Annotated[object, Depends(get_cross_app_user)]

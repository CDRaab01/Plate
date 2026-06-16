import uuid

from app.config import settings


async def test_register_returns_tokens(client):
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Alice", "email": f"alice_{uid}@test.com", "password": "secret123"},
    )
    assert resp.status_code == 201
    data = resp.json()
    assert "access_token" in data
    assert "refresh_token" in data
    assert data["token_type"] == "bearer"


async def test_register_short_password_returns_422(client):
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Alice", "email": f"short_{uid}@test.com", "password": "short"},
    )
    assert resp.status_code == 422


async def test_register_duplicate_email_returns_409(client):
    uid = uuid.uuid4().hex[:8]
    email = f"dup_{uid}@test.com"
    payload = {"name": "Alice", "email": email, "password": "secret123"}
    await client.post("/auth/register", json=payload)
    resp = await client.post("/auth/register", json=payload)
    assert resp.status_code == 409


async def test_login_success(client):
    uid = uuid.uuid4().hex[:8]
    email = f"login_{uid}@test.com"
    await client.post(
        "/auth/register", json={"name": "Alice", "email": email, "password": "mypassword"}
    )
    resp = await client.post("/auth/login", json={"email": email, "password": "mypassword"})
    assert resp.status_code == 200
    assert "access_token" in resp.json()


async def test_login_wrong_password_returns_401(client):
    uid = uuid.uuid4().hex[:8]
    email = f"fail_{uid}@test.com"
    await client.post(
        "/auth/register", json={"name": "Alice", "email": email, "password": "correctpw"}
    )
    resp = await client.post("/auth/login", json={"email": email, "password": "wrongpw12"})
    assert resp.status_code == 401


async def test_refresh_issues_new_access_token(client):
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Bob", "email": f"bob_{uid}@test.com", "password": "pw123456"},
    )
    refresh_token = resp.json()["refresh_token"]
    resp2 = await client.post("/auth/refresh", json={"refresh_token": refresh_token})
    assert resp2.status_code == 200
    assert "access_token" in resp2.json()


async def test_me_requires_auth(client):
    resp = await client.get("/users/me")
    assert resp.status_code == 401


async def test_me_returns_current_user(auth_client):
    resp = await auth_client.get("/users/me")
    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Test User"
    assert "email" in data
    assert "id" in data


async def test_health_check(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


async def test_register_without_invite_code_rejected_when_required(client, monkeypatch):
    monkeypatch.setattr(settings, "registration_invite_code", "let-me-in")
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Eve", "email": f"eve_{uid}@test.com", "password": "secret123"},
    )
    assert resp.status_code == 403


async def test_register_with_correct_invite_code_succeeds(client, monkeypatch):
    monkeypatch.setattr(settings, "registration_invite_code", "let-me-in")
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Adam",
            "email": f"adam_{uid}@test.com",
            "password": "secret123",
            "invite_code": "let-me-in",
        },
    )
    assert resp.status_code == 201
    assert "access_token" in resp.json()


async def test_hsts_header_emitted_when_enabled(client, monkeypatch):
    monkeypatch.setattr(settings, "hsts_enabled", True)
    resp = await client.get("/health")
    assert "max-age" in resp.headers.get("strict-transport-security", "")


async def test_hsts_header_absent_by_default(client):
    resp = await client.get("/health")
    assert "strict-transport-security" not in resp.headers

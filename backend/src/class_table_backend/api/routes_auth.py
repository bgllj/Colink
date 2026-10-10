from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from class_table_backend.auth.dependencies import AdminDep, SessionDep
from class_table_backend.auth.passwords import verify_password
from class_table_backend.auth.tokens import issue_token
from class_table_backend.persistence.repositories import AdminUserRepository

router = APIRouter(prefix="/auth", tags=["auth"])

LOGIN_FAILURE_DETAIL = "用户名或密码错误"


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class AdminMeOut(BaseModel):
    id: str
    username: str


@router.post("/login", response_model=LoginResponse)
def login(body: LoginRequest, session: SessionDep) -> LoginResponse:
    user = AdminUserRepository(session).get_by_username(body.username)
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail=LOGIN_FAILURE_DETAIL)
    token = issue_token(user_id=user.id, username=user.username)
    return LoginResponse(access_token=token, token_type="bearer")


@router.get("/me", response_model=AdminMeOut)
def me(admin: AdminDep) -> AdminMeOut:
    return AdminMeOut(id=admin.id, username=admin.username)

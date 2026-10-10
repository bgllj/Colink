from __future__ import annotations

import hashlib
import hmac
import os

ALGORITHM = "pbkdf2_sha256"
DEFAULT_ITERATIONS = 600_000
SALT_BYTES = 16


def hash_password(
    password: str,
    *,
    iterations: int = DEFAULT_ITERATIONS,
    salt: bytes | None = None,
) -> str:
    """Hash *password* as ``pbkdf2_sha256$<iterations>$<salt_hex>$<hash_hex>``."""
    if iterations < 1:
        raise ValueError("iterations must be positive")
    actual_salt = salt if salt is not None else os.urandom(SALT_BYTES)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), actual_salt, iterations
    )
    return f"{ALGORITHM}${iterations}${actual_salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    """Constant-time check of *password* against a stored hash string."""
    parts = stored.split("$")
    if len(parts) != 4:
        return False
    algorithm, iterations_s, salt_hex, hash_hex = parts
    if algorithm != ALGORITHM:
        return False
    try:
        iterations = int(iterations_s)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(hash_hex)
    except ValueError:
        return False
    if iterations < 1:
        return False
    candidate = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, iterations
    )
    return hmac.compare_digest(candidate, expected)

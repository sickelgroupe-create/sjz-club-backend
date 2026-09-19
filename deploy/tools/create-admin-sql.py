"""Generate a private, initial-admin activation SQL file; never contains a default password."""
import getpass
import os
from pathlib import Path

def main():
    try:
        import bcrypt
    except ImportError:
        raise SystemExit("Install the local dependency first: python -m pip install bcrypt")
    password = getpass.getpass("Set a NEW admin password (12-72 UTF-8 bytes): ")
    if password != getpass.getpass("Repeat password: "):
        raise SystemExit("Passwords do not match")
    raw = password.encode("utf-8")
    if not 12 <= len(raw) <= 72:
        raise SystemExit("Password must be 12-72 UTF-8 bytes")
    hashed = bcrypt.hashpw(raw, bcrypt.gensalt(rounds=12, prefix=b"2a")).decode("ascii")
    target = Path(__file__).resolve().parents[2] / "database" / "003-admin.local.sql"
    sql = ("-- PRIVATE: initial setup only; do not commit this file.\n"
           "UPDATE sys_user SET password='" + hashed + "',status='0',pwd_update_date=NOW() "
           "WHERE user_id=1 AND user_name='admin' AND password='!UNINITIALIZED!';\n"
           "SELECT ROW_COUNT() AS activated_admin_count;\n")
    fd = os.open(str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(sql)
    print("Created " + str(target))
    print("Import into the NEW database after 001-schema.sql and 002-seed.sql. Expect activated_admin_count=1.")
    print("Keep this file private; never commit it or reuse it to reset an existing account.")

if __name__ == "__main__":
    main()

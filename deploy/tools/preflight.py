"""Read-only deployment checks. Never sends orders, payments, refunds or SQL writes.

Run on the target server as the application's service user. This is not native
device, merchant-side reconciliation, backup recovery or commercial acceptance.
Only standard-library dependencies and (for --check-db) the mysql client are used.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shlex
import socket
import stat
import subprocess
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[2]


class Checks:
    def __init__(self):
        self.results = []

    def add(self, name, ok, guidance, warning=False):
        self.results.append({"check": name, "status": "PASS" if ok else ("REVIEW" if warning else "FAIL"),
                             "guidance": "" if ok else guidance})


def read_settings(path):
    """Parse a simple systemd EnvironmentFile; do not execute/source any content."""
    values = {}
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line.strip())
        if not match or match[1] in values:
            raise ValueError("Invalid or duplicate setting at line " + str(number))
        raw = match[2]
        if raw.startswith(("'", '"')):
            parts = shlex.split(raw, comments=False, posix=True)
            if len(parts) != 1:
                raise ValueError("Invalid quoted setting at line " + str(number))
            raw = parts[0]
        if any(x in raw for x in ("\x00", "\n", "\r", "$(", "${", "`")):
            raise ValueError("Unsupported interpolation at line " + str(number))
        values[match[1]] = raw
    return values


def real_secret(value, minimum=1):
    value = value or ""
    return len(value) >= minimum and not any(x in value.lower() for x in
        ("change_me", "example", "development-secret", "placeholder", "test-only", "audit-only"))


def https_base(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        host = parsed.hostname or ""
        return (parsed.scheme == "https" and bool(host) and host not in ("localhost", "127.0.0.1", "::1")
                and not host.endswith(".example.com") and host != "example.com" and not host.endswith(".invalid")
                and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
                and parsed.path in ("", "/") and parsed.port in (None, 443))
    except ValueError:
        return False


def config_checks(settings, checks):
    for key, length in (("DB_PASSWORD", 1), ("REDIS_PASSWORD", 1), ("TOKEN_SECRET", 32), ("CLUB_IDENTITY_ENCRYPTION_KEY", 32)):
        checks.add(key, real_secret(settings.get(key), length), "Set a private non-placeholder value; preserve the existing identity key when migrating encrypted records.")
    checks.add("independent signing/encryption keys", settings.get("TOKEN_SECRET") != settings.get("CLUB_IDENTITY_ENCRYPTION_KEY"), "Use separate keys for sessions and identity encryption.")
    checks.add("database account", bool(settings.get("DB_USERNAME")) and settings.get("DB_USERNAME") != "root", "Use a dedicated application database user.")
    checks.add("application listen address", settings.get("SERVER_ADDRESS") in ("127.0.0.1", "::1"), "Keep the application behind the reverse proxy on loopback.")
    checks.add("public API HTTPS", https_base(settings.get("PUBLIC_API_BASE", "")), "Configure the real HTTPS API origin without a path or credentials.")
    origins = settings.get("CORS_ALLOWED_ORIGINS", "").split(",")
    checks.add("CORS origins", bool(origins) and all(https_base(x.strip()) for x in origins), "List explicit real HTTPS frontend origins, not wildcards or placeholders.")
    checks.add("production profile", "sandbox" not in [p.strip().lower() for p in settings.get("SPRING_PROFILES_ACTIVE", "druid").split(",")]
               and settings.get("PAYMENT_MODE", "disabled").strip().lower() != "simulation"
               and settings.get("CLUB_AUTH_MODE", "wechat").strip().lower() != "standalone", "Never use standalone/simulation/sandbox for commercial data.")
    for key in ("SWAGGER_ENABLED", "DRUID_STAT_ENABLED", "DEVTOOLS_ENABLED"):
        checks.add(key + " disabled", settings.get(key, "false").lower() == "false", "Disable diagnostic/development endpoints in production.")
    virtual = settings.get("VIRTUAL_PAYMENT_ENABLED", "false").lower() == "true"
    native = settings.get("PAYMENT_MODE", "disabled").lower() == "wechat"
    checks.add("virtual payment enabled", virtual, "Virtual goods checkout requires the configured virtual-payment capability; credentials alone do not enable it.", warning=True)
    checks.add("WECHAT_LOGIN_MODE", settings.get("WECHAT_LOGIN_MODE", "").lower() == "real", "Set WECHAT_LOGIN_MODE=real for native login/binding; console phone authorization capability still needs manual verification.", warning=True)
    if virtual or native or settings.get("WECHAT_LOGIN_MODE", "").lower() == "real":
        checks.add("WeChat AppID", bool(re.fullmatch(r"wx[0-9a-fA-F]{16}", settings.get("WECHAT_APP_ID", ""))), "Set the correct Mini Program AppID.")
        checks.add("WeChat AppSecret", real_secret(settings.get("WECHAT_APP_SECRET"), 32), "Provide the matching private AppSecret outside Git.")
    if virtual:
        checks.add("virtual live environment", settings.get("VIRTUAL_PAYMENT_ENV") == "0", "Production must use environment 0; do not import sandbox payments.")
        checks.add("virtual OfferID", bool(re.fullmatch(r"[0-9]+", settings.get("VIRTUAL_PAYMENT_OFFER_ID", ""))), "Set the approved payment application ID.")
        checks.add("virtual live key", real_secret(settings.get("VIRTUAL_PAYMENT_LIVE_APP_KEY"), 32)
                   and bool(re.fullmatch(r"[A-Za-z0-9]{32}", settings.get("VIRTUAL_PAYMENT_LIVE_APP_KEY", ""))), "Configure the matching 32-character live AppKey.")
        checks.add("message token", bool(re.fullmatch(r"[A-Za-z0-9]{3,32}", settings.get("WECHAT_MESSAGE_TOKEN", "")))
                   and real_secret(settings.get("WECHAT_MESSAGE_TOKEN")), "Match the message callback token in the WeChat console.")
        try:
            aes = settings.get("WECHAT_MESSAGE_AES_KEY", "")
            valid_aes = len(aes) == 43 and len(base64.b64decode(aes + "=", validate=True)) == 32
        except (ValueError, TypeError):
            valid_aes = False
        checks.add("message AES key", valid_aes, "Match the 43-character callback AES key and safe mode in the WeChat console.")
    if native:
        for key in ("WECHAT_PAY_MCH_ID", "WECHAT_PAY_API_V3_KEY", "WECHAT_PAY_MERCHANT_SERIAL", "WECHAT_PAY_PUBLIC_KEY_ID"):
            checks.add(key, real_secret(settings.get(key), 32 if key == "WECHAT_PAY_API_V3_KEY" else 1), "Configure the matching merchant credential outside Git.")
        for key, path in (("WECHAT_PAY_NOTIFY_URL", "/app/pay/wechat/notify"), ("WECHAT_PAY_REFUND_NOTIFY_URL", "/app/pay/wechat/refund-notify")):
            checks.add(key, settings.get(key) == settings.get("PUBLIC_API_BASE", "").rstrip("/") + path, "Use the exact backend callback path for this deployment.")


def local_checks(settings, checks, settings_path):
    for key in ("RUOYI_PROFILE", "LOG_PATH"):
        path = Path(settings.get(key, ""))
        checks.add(key + " directory", path.is_absolute() and path.is_dir() and os.access(path, os.R_OK | os.W_OK | os.X_OK), "Precreate this directory and run this check as the service user. No write probe is performed.")
    for key in ("WECHAT_PAY_PRIVATE_KEY_PATH", "WECHAT_PAY_PUBLIC_KEY_PATH"):
        if settings.get("PAYMENT_MODE", "").lower() == "wechat":
            path = Path(settings.get(key, ""))
            checks.add(key + " readable", path.is_absolute() and path.is_file() and os.access(path, os.R_OK), "Provide a readable certificate/key file to the service user; this check does not validate cryptographic pairing.")
    if os.name == "posix":
        mode = stat.S_IMODE(Path(settings_path).stat().st_mode)
        checks.add("settings file access", not mode & 0o007 and not mode & 0o020, "Do not allow other users to read settings or the group to modify them (e.g. root:sjz 0640).")
    else:
        checks.add("settings file access", False, "Verify Windows ACLs manually; POSIX permission checks are unavailable.", warning=True)


def mysql_target(settings):
    url = settings.get("DB_URL", "")
    if not url.startswith("jdbc:mysql://"):
        raise ValueError("Unsupported database URL")
    parsed = urllib.parse.urlsplit(url[5:])
    database = parsed.path.lstrip("/")
    if not parsed.hostname or parsed.username or parsed.password or not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise ValueError("Invalid database target")
    return parsed.hostname, parsed.port or 3306, database


def expected_tables():
    return set(re.findall(r"CREATE TABLE(?: IF NOT EXISTS)?\s+`([^`]+)`", (ROOT / "database/001-schema.sql").read_text(encoding="utf-8"), re.I))


def database_checks(settings, checks, mysql="mysql"):
    host, port, database = mysql_target(settings)
    command = [mysql, "--no-defaults", "--protocol=TCP", "--connect-timeout=10", "--host=" + host, "--port=" + str(port),
               "--user=" + settings.get("DB_USERNAME", ""), "--default-character-set=utf8mb4", "--batch", "--skip-column-names", database]
    env = os.environ.copy()
    env["MYSQL_PWD"] = settings.get("DB_PASSWORD", "")

    def query(sql):
        # A read-only transaction adds an independent server-side write guard.
        result = subprocess.run(command, input="SET SESSION MAX_EXECUTION_TIME=10000; START TRANSACTION READ ONLY; " + sql + "; COMMIT;",
                                text=True, encoding="utf-8", env=env, capture_output=True, timeout=25)
        if result.returncode:
            raise RuntimeError("Database check failed; inspect connection, permissions and schema privately.")
        return result.stdout.strip()

    actual = set(query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()").splitlines())
    required = expected_tables()
    checks.add("required database tables", bool(required) and required.issubset(actual), "Apply only the missing migrations after backup; do not reimport 001/002 into a populated database.")
    if not required.issubset(actual):
        return
    columns = set(query("SELECT concat(table_name,'.',column_name) FROM information_schema.columns WHERE table_schema=DATABASE()").splitlines())
    for name in ("club_order.expiry_checked_at", "club_payment.refund_checked_at", "club_virtual_payment.payment_checked_at", "club_virtual_payment.refund_checked_at"):
        checks.add(name, name in columns, "Apply the corresponding one-time migration before deploying the new backend.")
    indexes = set(query("SELECT DISTINCT concat(table_name,'.',index_name) FROM information_schema.statistics WHERE table_schema=DATABASE()").splitlines())
    for name in ("club_order.idx_club_order_expiry_retry", "club_payment.idx_club_payment_refund_retry",
                 "club_wallet_record.idx_club_wallet_record_cursor", "club_aftersale_admin_request.idx_club_aftersale_admin_history",
                 "club_virtual_payment.idx_virtual_payment_check", "club_virtual_payment.idx_virtual_refund_check"):
        checks.add(name, name in indexes, "Verify the paired migration index, not just the new column.")
    checks.add("active administrator", int(query("SELECT count(*) FROM sys_user WHERE user_id=1 AND status='0' AND del_flag='0' AND password LIKE '$2%'") or "0") == 1,
               "Securely initialize or restore the administrator; do not use a published default password.")
    anomalies = {
        "receivable amount/status": "SELECT count(*) FROM club_provider_receivable WHERE (status='recovered' AND recovered_amount<amount) OR (status='outstanding' AND recovered_amount=amount) OR recovered_amount>amount OR recovered_amount<0",
        "legacy refund without intent": "SELECT count(*) FROM club_payment p JOIN club_aftersale a ON a.order_id=p.order_id WHERE p.mode='wechat' AND p.status='success' AND a.status='approved' AND (p.refund_no IS NULL OR p.refund_status IS NULL)",
        "wallet latest snapshot": "SELECT count(*) FROM club_wallet w JOIN club_wallet_record r ON r.user_id=w.user_id AND r.id=(SELECT max(r2.id) FROM club_wallet_record r2 WHERE r2.user_id=w.user_id) WHERE w.balance<>r.balance_after OR w.frozen<>r.frozen_after",
        "unresolved native refunds": "SELECT count(*) FROM club_payment WHERE mode='wechat' AND status='success' AND refund_status IN ('abnormal','closed')",
    }
    for name, sql in anomalies.items():
        count = int(query(sql) or "0")
        checks.add(name, count == 0, "Investigate " + str(count) + " flagged record(s) against the ledger and merchant records. Never overwrite them as successful.")
    if settings.get("VIRTUAL_PAYMENT_ENABLED", "false").lower() == "true":
        missing = query("SELECT count(*) FROM club_product_sku s JOIN club_product p ON p.id=s.product_id WHERE s.status='active' AND p.status='active' AND NOT EXISTS (SELECT 1 FROM club_virtual_goods g WHERE g.sku_id=s.id AND g.environment=0 AND g.published=1)")
        checks.add("live virtual goods mapping", int(missing or "0") == 0, "Publish and map every active sellable SKU in environment 0 before checkout acceptance.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def network_checks(settings, checks):
    base = settings.get("PUBLIC_API_BASE", "")
    if not https_base(base):
        checks.add("HTTPS health", False, "Correct PUBLIC_API_BASE before checking network reachability.")
    else:
        try:
            # Default TLS verification is mandatory. No redirect or token is sent.
            with urllib.request.build_opener(NoRedirect).open(base.rstrip("/") + "/health", timeout=10) as response:
                raw = response.read(65537)
                body = json.loads(raw) if len(raw) <= 65536 else {}
                ok = response.status == 200 and body.get("code") == 200 and body.get("data", {}).get("database") == "UP"
            checks.add("HTTPS health", ok, "Check HTTPS certificate, reverse proxy and backend database availability.")
        except Exception:
            checks.add("HTTPS health", False, "Check HTTPS certificate, reverse proxy and backend availability; error contents are deliberately not printed.")
    try:
        with socket.create_connection((settings.get("REDIS_HOST", "127.0.0.1"), int(settings.get("REDIS_PORT", "6379"))), timeout=5) as connection:
            connection.settimeout(5)
            stream = connection.makefile("rb")

            def redis_command(*parts):
                encoded = [str(p).encode("utf-8") for p in parts]
                connection.sendall(("*" + str(len(encoded)) + "\r\n").encode() + b"".join(("$" + str(len(p)) + "\r\n").encode() + p + b"\r\n" for p in encoded))
                return stream.readline(1024).strip()

            password = settings.get("REDIS_PASSWORD", "")
            if password and redis_command("AUTH", password) != b"+OK":
                raise ValueError("Redis authentication failed")
            if redis_command("SELECT", int(settings.get("REDIS_DATABASE", "0"))) != b"+OK":
                raise ValueError("Redis database selection failed")
            checks.add("Redis PING", redis_command("PING") == b"+PONG", "Check Redis authentication and the configured database.")
    except Exception:
        checks.add("Redis PING", False, "Check the configured Redis service from the target server. No data is written or cleared.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings-file", required=True, help="Private settings file outside Git; values are not printed")
    parser.add_argument("--check-db", action="store_true", help="Read-only SQL checks, including aggregate historical anomalies")
    parser.add_argument("--check-network", action="store_true", help="HTTPS /health GET and Redis AUTH/SELECT/PING only")
    parser.add_argument("--check-local-files", action="store_true", help="Read access/permission metadata as the service user")
    parser.add_argument("--mysql", default="mysql")
    args = parser.parse_args()
    checks = Checks()
    try:
        settings = read_settings(args.settings_file)
        config_checks(settings, checks)
        for enabled, name, operation in (
            (args.check_db, "database checks", lambda: database_checks(settings, checks, args.mysql)),
            (args.check_network, "network checks", lambda: network_checks(settings, checks)),
            (args.check_local_files, "local file checks", lambda: local_checks(settings, checks, args.settings_file))):
            if enabled:
                try:
                    operation()
                except Exception:
                    checks.add(name, False, "Check prerequisites/permissions privately. No secret or query result is printed.")
            else:
                checks.add(name, False, "Not executed. Use the matching flag on the intended server.", warning=True)
    except Exception:
        checks.add("settings file", False, "Cannot parse the private settings file. Use simple KEY=value syntax without interpolation or duplicate keys.")
    print(json.dumps({"checks": checks.results, "commercial_acceptance": "NOT_VERIFIED",
                      "still_required": ["Android/iOS native acceptance", "merchant-side historical reconciliation", "backup restore drill", "monitoring/alerts", "console domains and Apple capability", "service process uses these settings"]}, ensure_ascii=False, indent=2))
    return 1 if any(row["status"] == "FAIL" for row in checks.results) else 0


if __name__ == "__main__":
    raise SystemExit(main())

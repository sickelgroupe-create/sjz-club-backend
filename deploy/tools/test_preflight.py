import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import preflight


class PreflightTests(unittest.TestCase):
    def settings(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private.env"
            path.write_text(text, encoding="utf-8")
            return preflight.read_settings(path)

    def test_quoted_values_and_empty_value(self):
        self.assertEqual(self.settings("# header\nDB_URL='jdbc:mysql://localhost/db?a=b&c=d'\nWECHAT_APP_ID=\nTOKEN_SECRET=a#b\n"),
                         {"DB_URL": "jdbc:mysql://localhost/db?a=b&c=d", "WECHAT_APP_ID": "", "TOKEN_SECRET": "a#b"})

    def test_duplicate_or_invalid_settings_rejected_without_value(self):
        for source in ("A=first\nA=secret-value", "export A=secret-value", 'A="secret-value" trailing'):
            with self.assertRaises(ValueError) as error:
                self.settings(source)
            self.assertNotIn("secret-value", str(error.exception))

    def test_shell_interpolation_never_executed(self):
        for value in ("$(hostname)", "`hostname`", "${PRIVATE_KEY}"):
            with self.assertRaises(ValueError):
                self.settings("A=" + value)

    def test_public_origin_checks(self):
        self.assertTrue(preflight.https_base("https://api.merchant.test"))
        for value in ("http://api.merchant.test", "https://localhost", "https://a.invalid", "https://api.example.com",
                      "https://user:secret@api.merchant.test", "https://api.merchant.test/path", "https://api.merchant.test?q=secret", "https://api.merchant.test:8443", "https://api.merchant.test:invalid"):
            self.assertFalse(preflight.https_base(value), value)

    def test_template_fails_safely(self):
        checks = preflight.Checks()
        settings = preflight.read_settings(preflight.ROOT / "deploy/settings.env.example")
        preflight.config_checks(settings, checks)
        self.assertTrue(any(r["status"] == "FAIL" for r in checks.results))
        self.assertNotIn(settings["TOKEN_SECRET"], json.dumps(checks.results))

    def test_real_is_the_supported_wechat_login_mode(self):
        for value, expected in (("real", "PASS"), ("wechat", "REVIEW"), ("disabled", "REVIEW")):
            checks = preflight.Checks()
            preflight.config_checks({"WECHAT_LOGIN_MODE": value}, checks)
            self.assertEqual(next(r["status"] for r in checks.results if r["check"] == "WECHAT_LOGIN_MODE"), expected)

    def test_production_sandbox_rejected(self):
        for entry in ({"SPRING_PROFILES_ACTIVE": "druid,sandbox"}, {"SPRING_PROFILES_ACTIVE": "druid, sandbox"},
                      {"PAYMENT_MODE": "simulation"}, {"PAYMENT_MODE": "SIMULATION"},
                      {"CLUB_AUTH_MODE": "standalone"}, {"CLUB_AUTH_MODE": "STANDALONE"}):
            checks = preflight.Checks()
            preflight.config_checks(entry, checks)
            self.assertEqual(next(r["status"] for r in checks.results if r["check"] == "production profile"), "FAIL")

    def test_native_callback_requires_correct_path(self):
        checks = preflight.Checks()
        preflight.config_checks({"PAYMENT_MODE": "wechat", "PUBLIC_API_BASE": "https://api.merchant.test",
                                 "WECHAT_PAY_NOTIFY_URL": "https://api.merchant.test/app/pay/wechat/notify",
                                 "WECHAT_PAY_REFUND_NOTIFY_URL": "https://api.merchant.test/wrong"}, checks)
        self.assertEqual(next(r["status"] for r in checks.results if r["check"] == "WECHAT_PAY_NOTIFY_URL"), "PASS")
        self.assertEqual(next(r["status"] for r in checks.results if r["check"] == "WECHAT_PAY_REFUND_NOTIFY_URL"), "FAIL")

    def test_virtual_sandbox_is_not_live_acceptance(self):
        checks = preflight.Checks()
        preflight.config_checks({"VIRTUAL_PAYMENT_ENABLED": "true", "VIRTUAL_PAYMENT_ENV": "1", "WECHAT_MESSAGE_AES_KEY": "!" * 43}, checks)
        for name in ("virtual live environment", "message AES key", "virtual live key"):
            self.assertEqual(next(r["status"] for r in checks.results if r["check"] == name), "FAIL")

    def test_mysql_target_rejects_credentials_and_injection(self):
        self.assertEqual(preflight.mysql_target({"DB_URL": "jdbc:mysql://127.0.0.1:33391/test_sandbox?a=b"}), ("127.0.0.1", 33391, "test_sandbox"))
        for url in ("jdbc:mysql://user:secret@host/db", "jdbc:mysql://host/db;DROP", "jdbc:mysql://host/db/a", "jdbc:postgresql://host/db"):
            with self.assertRaises(ValueError):
                preflight.mysql_target({"DB_URL": url})

    def test_schema_includes_new_history_table(self):
        tables = preflight.expected_tables()
        self.assertEqual(len(tables), 75)
        self.assertIn("club_aftersale_admin_request", tables)

    def test_database_uses_readonly_transactions_and_no_password_argument(self):
        tables = "\n".join(preflight.expected_tables())
        columns = "\n".join(("club_order.expiry_checked_at", "club_payment.refund_checked_at", "club_virtual_payment.payment_checked_at", "club_virtual_payment.refund_checked_at"))
        indexes = "\n".join(("club_order.idx_club_order_expiry_retry", "club_payment.idx_club_payment_refund_retry", "club_wallet_record.idx_club_wallet_record_cursor", "club_aftersale_admin_request.idx_club_aftersale_admin_history", "club_virtual_payment.idx_virtual_payment_check", "club_virtual_payment.idx_virtual_refund_check"))
        outputs = iter((tables, columns, indexes, "1", "0", "0", "0", "0"))
        def execute(command, **options):
            self.assertNotIn("private-db-password", repr(command))
            self.assertEqual(options["env"]["MYSQL_PWD"], "private-db-password")
            self.assertIn("START TRANSACTION READ ONLY; SELECT ", options["input"])
            self.assertTrue(options["input"].endswith("; COMMIT;"))
            return subprocess.CompletedProcess(command, 0, next(outputs), "")
        checks = preflight.Checks()
        with patch("preflight.subprocess.run", side_effect=execute):
            preflight.database_checks({"DB_URL": "jdbc:mysql://127.0.0.1/local_sandbox", "DB_USERNAME": "app", "DB_PASSWORD": "private-db-password"}, checks)
        self.assertTrue(all(r["status"] == "PASS" for r in checks.results))

    def test_missing_schema_stops_business_queries(self):
        checks = preflight.Checks()
        with patch("preflight.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "sys_user", "")) as execute:
            preflight.database_checks({"DB_URL": "jdbc:mysql://127.0.0.1/local_sandbox"}, checks)
        self.assertEqual(execute.call_count, 1)
        self.assertEqual(checks.results[0]["status"], "FAIL")

    def test_database_failure_does_not_expose_server_output(self):
        with patch("preflight.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "sensitive query rows", "secret credentials")):
            with self.assertRaises(RuntimeError) as error:
                preflight.database_checks({"DB_URL": "jdbc:mysql://127.0.0.1/local_sandbox"}, preflight.Checks())
        self.assertNotIn("secret", str(error.exception))
        self.assertNotIn("sensitive", str(error.exception))

    def test_redirect_not_followed(self):
        self.assertIsNone(preflight.NoRedirect().redirect_request(None, None, 302, "", {}, "http://untrusted.invalid"))

    def test_uppercase_wechat_still_checks_certificate_files(self):
        checks = preflight.Checks()
        with tempfile.TemporaryDirectory() as directory:
            settings_path = Path(directory) / "private.env"
            settings_path.write_text("PAYMENT_MODE=WECHAT\n", encoding="utf-8")
            preflight.local_checks({"PAYMENT_MODE": "WECHAT"}, checks, settings_path)
        for name in ("WECHAT_PAY_PRIVATE_KEY_PATH readable", "WECHAT_PAY_PUBLIC_KEY_PATH readable"):
            self.assertEqual(next(r["status"] for r in checks.results if r["check"] == name), "FAIL")

    def test_cli_never_declares_commercial_acceptance(self):
        output = io.StringIO()
        with patch("sys.argv", ["preflight.py", "--settings-file", "does-not-exist.env"]), contextlib.redirect_stdout(output):
            self.assertEqual(preflight.main(), 1)
        report = json.loads(output.getvalue())
        self.assertEqual(report["commercial_acceptance"], "NOT_VERIFIED")
        self.assertGreater(len(report["still_required"]), 0)


if __name__ == "__main__":
    unittest.main()

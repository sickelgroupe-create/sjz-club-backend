"""Import the clean baseline into an existing EMPTY database. Never upgrades a populated DB."""
import argparse
import getpass
import os
from pathlib import Path
import re
import subprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql", default="mysql", help="Path to mysql client")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    parser.add_argument("--database", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]+", args.database):
        raise SystemExit("Database name must contain only letters, digits and underscores")
    env = os.environ.copy()
    env["MYSQL_PWD"] = getpass.getpass("Database password: ")
    command = [args.mysql, "--no-defaults", "--protocol=TCP", "--host=" + args.host,
               "--port=" + str(args.port), "--user=" + args.user, "--default-character-set=utf8mb4",
               "--batch", "--skip-column-names", args.database]
    def run(sql):
        result = subprocess.run(command, input=sql, text=True, encoding="utf-8",
                                env=env, capture_output=True)
        if result.returncode:
            # Do not print statements/data or credentials on failure.
            raise SystemExit("Database operation failed. Check connection/permissions and inspect this new database before retrying.")
        return result.stdout.strip()
    count = run("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();")
    if count != "0":
        raise SystemExit("Refused: target database is not empty. No schema or seed was imported.")
    expected = args.host + ":" + str(args.port) + "/" + args.database
    if input("Type target to confirm [" + expected + "]: ") != expected:
        raise SystemExit("Cancelled")
    directory = Path(__file__).resolve().parents[2] / "database"
    for name in ("001-schema.sql", "002-seed.sql"):
        run((directory / name).read_text(encoding="utf-8"))
        print("Imported " + name)
    expected = set(re.findall(r"CREATE TABLE(?: IF NOT EXISTS)?\s+`([^`]+)`",
                             (directory / "001-schema.sql").read_text(encoding="utf-8"), re.I))
    actual = set(run("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE();").splitlines())
    if not expected or actual != expected:
        raise SystemExit("Imported table names do not match the checked-in baseline. Inspect this new database before continuing.")
    print("Verified " + str(len(actual)) + " baseline tables. Admin is disabled; initialize your private admin password next.")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Renders MySQL DDL for the CDC acceptance fixtures: tables that keep the HLD 7.4 source contract
(positive version CHECK, version and key guards, key reservation), and deliberately broken ones.

  mysql_contract.py setup <database>           the databases, grants and fixture tables
  mysql_contract.py table <database> <table>   one more contract table (for new-table enrollment)
  mysql_contract.py heartbeat <database>       the heartbeat table, writable by colvenna_beat
  mysql_contract.py reset <database>           drops the fixture databases
"""
import sys

GUARD_DB = "{db}_guard"


def contract_table(db, table, columns, keys=("id",)):
    """CREATE TABLE plus the contract's three guard triggers (docs/history/m1/mysql-cdc.md)."""
    key_cols = ", ".join(keys)
    key_changed = " OR ".join(f"NEW.{k} <> OLD.{k}" for k in keys)
    key_values = ", ".join(f"CAST(NEW.{k} AS CHAR)" for k in keys)
    guard = GUARD_DB.format(db=db)
    return f"""
CREATE TABLE IF NOT EXISTS `{db}`.`{table}` (
    {columns},
    version_no BIGINT NOT NULL,
    PRIMARY KEY ({key_cols}),
    CONSTRAINT {table}_positive_version CHECK (version_no > 0) ENFORCED
) ENGINE=InnoDB;
DROP TRIGGER IF EXISTS `{db}`.`{table}_version_guard`;
DROP TRIGGER IF EXISTS `{db}`.`{table}_key_immutable`;
DROP TRIGGER IF EXISTS `{db}`.`{table}_key_reserve`;
DELIMITER //
CREATE TRIGGER `{db}`.`{table}_version_guard` BEFORE UPDATE ON `{db}`.`{table}` FOR EACH ROW
BEGIN
    IF NEW.version_no <= OLD.version_no THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NON_MONOTONIC_VERSION_NO';
    END IF;
END//
CREATE TRIGGER `{db}`.`{table}_key_immutable` BEFORE UPDATE ON `{db}`.`{table}` FOR EACH ROW
BEGIN
    IF {key_changed} THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PRIMARY_KEY_IMMUTABLE';
    END IF;
END//
CREATE TRIGGER `{db}`.`{table}_key_reserve` BEFORE INSERT ON `{db}`.`{table}` FOR EACH ROW
BEGIN
    INSERT INTO `{guard}`.used_keys (source_table, source_key)
    VALUES ('{table}', CONCAT_WS('|', {key_values}));
END//
DELIMITER ;
"""


def setup(db):
    guard = GUARD_DB.format(db=db)
    out = [f"""
CREATE DATABASE IF NOT EXISTS `{db}`;
CREATE DATABASE IF NOT EXISTS `{guard}`;
CREATE TABLE IF NOT EXISTS `{guard}`.used_keys (
    source_table VARCHAR(64) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (source_table, source_key)
) ENGINE=InnoDB;
GRANT SELECT ON `{db}`.* TO 'colvenna_cdc'@'%';
GRANT SELECT, TRIGGER ON `{db}`.* TO 'colvenna_inspect'@'%';
"""]
    out.append(contract_table(db, "orders",
        "id BIGINT NOT NULL, customer_id BIGINT NOT NULL, amount DECIMAL(12,2) NOT NULL,"
        " state VARCHAR(32) NOT NULL, note VARCHAR(64) NULL"))
    out.append(contract_table(db, "customers",
        "id BIGINT NOT NULL, name VARCHAR(64) NOT NULL, region VARCHAR(16) NOT NULL"))
    # A composite key and a fixed-length binary column (Debezium base64 mode).
    out.append(contract_table(db, "tokens",
        "tenant INT NOT NULL, id BIGINT NOT NULL, token BINARY(16) NOT NULL", keys=("tenant", "id")))
    # Rejected on admission, each for one reason.
    out.append(f"""
CREATE TABLE IF NOT EXISTS `{db}`.no_key (id BIGINT NOT NULL, version_no BIGINT NOT NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS `{db}`.no_guard (
    id BIGINT NOT NULL PRIMARY KEY,
    version_no BIGINT NOT NULL,
    CONSTRAINT no_guard_positive_version CHECK (version_no > 0) ENFORCED
) ENGINE=InnoDB;
""")
    out.append(contract_table(db, "blobs", "id BIGINT NOT NULL, payload VARBINARY(32) NOT NULL"))
    # Every type admission accepts, nullable, so values and NULLs both cross.
    out.append(contract_table(db, "typed",
        "id BIGINT NOT NULL, t_tiny TINYINT NULL, t_small SMALLINT NULL, t_medium MEDIUMINT NULL,"
        " t_int INT NULL, t_dec DECIMAL(10,3) NULL, t_float FLOAT NULL, t_double DOUBLE NULL,"
        " t_char CHAR(4) NULL, t_varchar VARCHAR(20) NULL, t_tinytext TINYTEXT NULL,"
        " t_text TEXT NULL, t_mediumtext MEDIUMTEXT NULL, t_longtext LONGTEXT NULL,"
        " t_date DATE NULL, t_datetime DATETIME(3) NULL"))
    out.append(f"""
INSERT INTO `{db}`.typed
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50)
  SELECT i, IF(i % 7 = 0, NULL, i % 100 - 50), i * -13, i * 1009, i * 100003, i * 1.125,
         i + 0.5, i * 0.25, CONCAT('c', i % 10), CONCAT('v-', i), 'tiny', REPEAT('t', i),
         'medium', 'long', DATE_ADD('2026-10-15', INTERVAL i DAY),
         TIMESTAMPADD(SECOND, i, '2026-10-15 01:02:03.456'), 1
  FROM n;
""")
    return "".join(out)


def reset(db):
    return f"DROP DATABASE IF EXISTS `{db}`;\nDROP DATABASE IF EXISTS `{GUARD_DB.format(db=db)}`;\n"


if __name__ == "__main__":
    command, db = sys.argv[1], sys.argv[2]
    if command == "setup":
        print(setup(db))
    elif command == "table":
        print(contract_table(db, sys.argv[3],
            "id BIGINT NOT NULL, label VARCHAR(64) NOT NULL, amount DECIMAL(12,2) NOT NULL"))
    elif command == "heartbeat":
        # The operator writes it each round (LLD "监控与数据质量") as colvenna_beat, granted this table only.
        print(contract_table(db, "colvenna_heartbeat", "id INT NOT NULL, beat_at DATETIME(6) NOT NULL"))
        print(f"GRANT SELECT, INSERT, UPDATE ON `{db}`.`colvenna_heartbeat` TO 'colvenna_beat'@'%';")
    elif command == "reset":
        print(reset(db))
    else:
        sys.exit(__doc__)

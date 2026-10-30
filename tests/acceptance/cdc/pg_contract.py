#!/usr/bin/env python3
"""Renders PostgreSQL DDL for the CDC acceptance fixtures, in the database the run uses: tables that
keep the HLD 7.4 source contract (positive version CHECK, a version guard, key reservation, REPLICA
IDENTITY FULL, in the publication), a partitioned one read from its leaves, and broken ones.

  pg_contract.py database <database>          CREATE DATABASE (run in the postgres database)
  pg_contract.py setup <publication>          schema objects, grants and the publication
  pg_contract.py table <publication> <table>  one more contract table, added to the publication
  pg_contract.py reset <database>             DROP DATABASE (run in the postgres database)
"""
import sys


def guards(table):
    """The version guard and key reservation, as trigger functions."""
    return f"""
CREATE OR REPLACE FUNCTION {table}_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version_no <= OLD.version_no THEN
        RAISE EXCEPTION 'NON_MONOTONIC_VERSION_NO' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id THEN
        RAISE EXCEPTION 'PRIMARY_KEY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION {table}_key_reserve() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO guard.used_keys (source_table, source_key) VALUES ('{table}', NEW.id::text);
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS {table}_version_guard ON {table};
CREATE TRIGGER {table}_version_guard BEFORE UPDATE ON {table}
    FOR EACH ROW EXECUTE FUNCTION {table}_version_guard();
DROP TRIGGER IF EXISTS {table}_key_reserve ON {table};
CREATE TRIGGER {table}_key_reserve BEFORE INSERT ON {table}
    FOR EACH ROW EXECUTE FUNCTION {table}_key_reserve();
"""


def contract(table, columns):
    """A plain contract table: key, version, REPLICA IDENTITY FULL and the guards."""
    return f"""
CREATE TABLE IF NOT EXISTS {table} (
    id bigint NOT NULL PRIMARY KEY,
    {columns},
    version_no bigint NOT NULL CHECK (version_no > 0)
);
ALTER TABLE {table} REPLICA IDENTITY FULL;
{guards(table)}
"""


def setup(publication):
    return f"""
CREATE SCHEMA IF NOT EXISTS guard;
CREATE TABLE IF NOT EXISTS guard.used_keys (
    source_table text NOT NULL,
    source_key text NOT NULL,
    PRIMARY KEY (source_table, source_key)
);
CREATE TABLE IF NOT EXISTS accounts (
    id bigint NOT NULL PRIMARY KEY,
    owner varchar(64) NOT NULL,
    balance numeric(14,2) NOT NULL,
    version_no bigint NOT NULL CHECK (version_no > 0)
);
ALTER TABLE accounts REPLICA IDENTITY FULL;
{guards("accounts")}
CREATE TABLE IF NOT EXISTS payments (
    id bigint NOT NULL,
    region text NOT NULL,
    amount numeric(14,2) NOT NULL,
    version_no bigint NOT NULL CHECK (version_no > 0),
    PRIMARY KEY (id, region)
) PARTITION BY LIST (region);
CREATE TABLE IF NOT EXISTS payments_east PARTITION OF payments FOR VALUES IN ('east');
CREATE TABLE IF NOT EXISTS payments_west PARTITION OF payments FOR VALUES IN ('west');
ALTER TABLE payments REPLICA IDENTITY FULL;
ALTER TABLE payments_east REPLICA IDENTITY FULL;
ALTER TABLE payments_west REPLICA IDENTITY FULL;
{guards("payments")}
-- Rejected: the default replica identity carries only the key in before images.
CREATE TABLE IF NOT EXISTS partial_identity (
    id bigint NOT NULL PRIMARY KEY,
    version_no bigint NOT NULL CHECK (version_no > 0)
);
{guards("partial_identity")}
-- Rejected: not in the publication.
CREATE TABLE IF NOT EXISTS unpublished (
    id bigint NOT NULL PRIMARY KEY,
    version_no bigint NOT NULL CHECK (version_no > 0)
);
ALTER TABLE unpublished REPLICA IDENTITY FULL;
{guards("unpublished")}
-- Every type admission accepts, nullable, so values and NULLs both cross.
CREATE TABLE IF NOT EXISTS typed (
    id bigint NOT NULL PRIMARY KEY,
    t_small int2, t_int int4, t_num numeric(10,3), t_real float4, t_double float8,
    t_varchar varchar(20), t_char char(4), t_text text, t_date date, t_ts timestamp(3),
    version_no bigint NOT NULL CHECK (version_no > 0)
);
ALTER TABLE typed REPLICA IDENTITY FULL;
{guards("typed")}
INSERT INTO typed SELECT i, CASE WHEN i % 7 = 0 THEN NULL ELSE (i * -13)::int2 END, i * 100003,
    i * 1.125, i + 0.5, i * 0.25, 'v-' || i, 'c' || (i % 10), repeat('t', i),
    date '2026-10-15' + i, timestamp '2026-10-15 01:02:03.456' + i * interval '1 second', 1
    FROM generate_series(1, 50) i;
{contract("ledger", "label text NOT NULL, amount numeric(14,2) NOT NULL")}
INSERT INTO ledger VALUES (1, 'l1', 1, 1), (2, 'l2', 2, 1);
DROP PUBLICATION IF EXISTS {publication};
CREATE PUBLICATION {publication}
    FOR TABLE accounts, payments_east, payments_west, partial_identity, typed, ledger
    WITH (publish_via_partition_root = false);
GRANT USAGE ON SCHEMA public TO colvenna_pgcdc;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO colvenna_pgcdc;
"""


if __name__ == "__main__":
    command, name = sys.argv[1], sys.argv[2]
    if command == "database":
        print(f"CREATE DATABASE {name};\nGRANT CONNECT ON DATABASE {name} TO colvenna_pgcdc;")
    elif command == "setup":
        print(setup(name))
    elif command == "table":
        print(contract(sys.argv[3], "label text NOT NULL, amount numeric(14,2) NOT NULL"))
        print(f"GRANT SELECT ON {sys.argv[3]} TO colvenna_pgcdc;")
        print(f"ALTER PUBLICATION {name} ADD TABLE {sys.argv[3]};")
    elif command == "reset":
        print(f"DROP DATABASE IF EXISTS {name} WITH (FORCE);")
    else:
        sys.exit(__doc__)

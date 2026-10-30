#!/usr/bin/env bash
# What run-repro.sh and run-pipeline.sh share: fresh sources in Docker on this machine, listening
# on loopback only, with throwaway passwords (synthetic rows).

# mysql_up <container>: a fresh MySQL 8.0 with GTIDs and a row binlog; prints its host port.
mysql_up() {
  docker rm -f "$1" >/dev/null 2>&1 || true
  docker run -d --name "$1" -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=shop -p 127.0.0.1::3306 \
    mysql:8.0.39 --server-id=1 --log-bin=mysql-bin --binlog-format=ROW --gtid-mode=ON \
    --enforce-gtid-consistency=ON >/dev/null
  for _ in $(seq 1 60); do
    docker exec -e MYSQL_PWD=test "$1" mysql -uroot -e 'SELECT 1' shop >/dev/null 2>&1 && break
    sleep 2
  done
  docker port "$1" 3306/tcp | head -1 | cut -d: -f2
}

# postgres_up <container>: a fresh PostgreSQL 16 with logical replication; prints its host port.
postgres_up() {
  docker rm -f "$1" >/dev/null 2>&1 || true
  docker run -d --name "$1" -e POSTGRES_PASSWORD=test -e POSTGRES_DB=shop -p 127.0.0.1::5432 \
    postgres:16.4 -c wal_level=logical -c max_replication_slots=10 -c max_wal_senders=10 >/dev/null
  for _ in $(seq 1 60); do
    docker exec "$1" pg_isready -U postgres -d shop >/dev/null 2>&1 && break
    sleep 2
  done
  sleep 3
  docker port "$1" 5432/tcp | head -1 | cut -d: -f2
}

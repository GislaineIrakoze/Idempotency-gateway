#!/usr/bin/env sh
set -eu

PORT="${TEST_PORT:-8090}"
mkdir -p out
javac -d out $(find src/main/java src/test/java -name "*.java")
java -DPORT="$PORT" -cp out com.igirepay.gateway.Application &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 2
java -DBASE_URL="http://127.0.0.1:$PORT" -cp out com.igirepay.gateway.AcceptanceTest


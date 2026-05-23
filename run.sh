#!/usr/bin/env sh
set -eu

mkdir -p out
javac -d out $(find src/main/java -name "*.java")
java -cp out com.igirepay.gateway.Application


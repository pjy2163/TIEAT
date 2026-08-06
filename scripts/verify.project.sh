#!/usr/bin/env bash
set -euo pipefail

./gradlew :apps:api:check --no-daemon

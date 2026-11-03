#!/usr/bin/env bash
# Build the Maven modules; artifacts remain in each module's target directory.
# Usage: ./build.sh [Maven options]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ -f ./mvnw ]; then
  bash ./mvnw -DskipTests package "$@"
else
  mvn -DskipTests package "$@"
fi

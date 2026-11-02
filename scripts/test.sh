#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
gradle :app:assembleDebug :app:lintDebug --no-daemon

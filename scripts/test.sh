#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon

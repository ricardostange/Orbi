#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s scripts -p 'test_*.py' -v
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon


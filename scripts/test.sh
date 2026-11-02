#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ECJ_JAR:?Set ECJ_JAR to Eclipse ECJ 3.33.0 jar}"
mkdir -p build/test
java -jar "$ECJ_JAR" -8 -proc:none -d build/test src/br/com/assistente/teste/CommandRouter.java src/br/com/assistente/teste/NoteMatcher.java tests/RouterTest.java
java -cp build/test RouterTest

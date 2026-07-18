#!/usr/bin/env bash
# hikari standalone Clojure test suite.
set -euo pipefail
cd "$(dirname "$0")"
exec clojure -M:test

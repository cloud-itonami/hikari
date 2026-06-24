#!/usr/bin/env bash
# hikari — clj/bb test suite (ADR-2606160842 py→clj port wave); wired into the fleet
# green-check. Runs all cljc test namespaces via babashka from the repo root (for the
# :paths config in bb.edn). Covers the two operational method ports (microgrid control
# loop + panel-install motion) and the two gated cell state machines (grid_edge dispatch
# + solar_pv_install job-commit) that replaced their Python twins.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec bb -e '(def nss (quote [hikari.methods.test-microgrid
                             hikari.methods.test-panel-install
                             hikari.methods.test-agent
                             hikari.cells.grid-edge.test-state-machine
                             hikari.cells.solar-pv-install.test-state-machine
                             hikari.cells.consumption-audit.test-state-machine
                             hikari.cells.geothermal-micro.test-state-machine
                             hikari.cells.storage-battery.test-state-machine]))
              (apply require (quote clojure.test) nss)
              (let [r (apply clojure.test/run-tests nss)]
                (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))'

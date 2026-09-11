#!/usr/bin/env nbb
;; run_tests.cljs — hikari 光 test suite.
;;
;;   nbb run_tests.cljs          # repo root; nbb.edn supplies :paths src/test + kotoba.lang.text
;;
;; Runs every clojure.test namespace under test/: the microgrid control loop
;; (frequency restore, ROCOF relay, swing-equation physics), the Otete panel-install
;; motion planner (IK, safety envelope, N1 / G15-G7 / G8 gates), the shared planar-arm
;; kinematics, the five cell state machines, the agent handlers + settlement split,
;; the cljc actor boundary (hikari.murakumo), and the descriptor cross-checks
;; (manifest.jsonld ↔ manifest.edn ↔ cells/*.edn ↔ lex ↔ agent).
;;
;; ── why this file exists (2026-09-11) ────────────────────────────────────────
;; The previous runner (run_tests.sh, `exec bb` + `cd ../..`) was written for the
;; etzhayyim monorepo, where this directory sat at 20-actors/hikari and bb.edn at
;; the monorepo root put `hikari/methods/*.cljc` on the classpath. In this standalone
;; repo that `cd` lands outside the repo and bb stops at
;; `Could not locate hikari/methods/test_microgrid.bb ... on classpath` — measured
;; on origin/main d52cceb, exit 1, 0 tests. The 11 cljc suites under methods/ and
;; cells/ had not run once since the 2026-07-18 standalone migration, and
;; deps.edn's `:paths ["src"]` / `:extra-paths ["test"]` did not name a single one
;; of them. They now live where those two paths look, so require-by-name resolves
;; on nbb, on ClojureScript, and on the JVM alias alike.
;;
;; workspace rule: script host is nbb; .sh / .mjs / bb are not written anew.
(ns run-tests
  (:require [clojure.test :as t]
            [hikari.murakumo-test]
            [hikari.descriptor-test]
            [hikari.methods.test-agent]
            [hikari.methods.test-microgrid]
            [hikari.methods.test-microgrid-physics]
            [hikari.methods.test-initial-rocof]
            [hikari.methods.test-panel-install]
            [hikari.methods.test-substrate-kinematics]
            [hikari.cells.grid-edge.test-state-machine]
            [hikari.cells.solar-pv-install.test-state-machine]
            [hikari.cells.storage-battery.test-state-machine]
            [hikari.cells.geothermal-micro.test-state-machine]
            [hikari.cells.consumption-audit.test-state-machine]))

(def green-marker
  "scripts/maturity-loop/mutations.edn の `:green-marker`。全部緑のときだけ出る ——
  出力に現れるかどうかで mutation が噛んだかを判定するので、緑でないときに
  印字してはならない。"
  "hikari suite: all green")

;; NOT run here: py/test_agent.clj. It is a bb-hosted duplicate of
;; test/hikari/methods/test_agent.cljc (`#!/usr/bin/env bb`, ns hikari.py.test-agent
;; over py/agent.clj) — same 13 cases, snake_case keys. bb is a retired script host
;; workspace-wide (ADR-2607173000) and the .clj extension is invisible to nbb's
;; resolver, so it is left as the historical port; the cases it holds run above
;; through hikari.methods.test-agent.
;;
;; REMOVED (2026-09-11): methods/test_charter_gates.cljc. It read
;; `<monorepo>/00-contracts/lexicons/com/etzhayyim/hikari/*.json` through
;; java.io.File + cheshire and asserted a lexicon vocabulary (componentType /
;; magnetAttestation / chemistryAttestation knownValues) that the lexicons in THIS
;; repo never carried — lexicons/**/*.json here are the EAVT projection of lex/*.edn
;; (lexicon-projection.edn). Ported as written it would have been 6 red of 7.
;; test/hikari/descriptor_test.cljc is its replacement: it keeps the one assertion
;; that holds here (the full G1–G14 gate set) and pins the seams this tree does have.

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (cond
    ;; 0 本の suite は緑ではない。standalone 移行から 2 ヶ月、この repo の suite は
    ;; 「走らなかった」を「落ちなかった」と同じ顔で持っていた。測れなかった検査は
    ;; pass でも fail でもなく、専用の値で終わる。
    (zero? (:test m))
    (do (println "\nhikari suite: REFUSED — 0 tests collected; a suite that ran nothing is not green")
        (js/process.exit 2))
    (t/successful? m)
    (println (str "\n" green-marker))
    :else
    (do (println "\nhikari suite: FAILED")
        (js/process.exit 1))))

(t/run-tests 'hikari.murakumo-test
             'hikari.descriptor-test
             'hikari.methods.test-agent
             'hikari.methods.test-microgrid
             'hikari.methods.test-microgrid-physics
             'hikari.methods.test-initial-rocof
             'hikari.methods.test-panel-install
             'hikari.methods.test-substrate-kinematics
             'hikari.cells.grid-edge.test-state-machine
             'hikari.cells.solar-pv-install.test-state-machine
             'hikari.cells.storage-battery.test-state-machine
             'hikari.cells.geothermal-micro.test-state-machine
             'hikari.cells.consumption-audit.test-state-machine)

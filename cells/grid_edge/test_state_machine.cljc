(ns hikari.cells.grid-edge.test-state-machine
  "Tests for the hikari grid_edge gated cell state machine (ADR-2605261100 port).
  1:1 port of the grid_edge cases in cells/test_state_machines.py (pytest → clojure.test):
  commission → dispatch happy path (member-signed, dry-run, witness quorum), the N1
  non-civilian-use refusal, and the G15/G7 server-signature refusal."
  (:require [clojure.test :refer [deftest is]]
            [hikari.cells.grid-edge.state-machine :as sm]))

(def WITNESS ["did:web:etzhayyim.com:kuniumi:robot:otete-01"
              "did:web:etzhayyim.com:kuniumi:robot:mimi-01"])

(deftest test-grid-edge-happy-path-commits-dry-run-dispatch
  (let [s1 (sm/transition-commission {"load_step_kw" 140.0})
        s2 (-> s1
               (assoc "member_sig" "m:ed25519:demo" "witness_sigs" WITNESS)
               sm/transition-commit-dispatch)
        dispatch (get-in s2 ["cell_state" "payload" "dispatch"])]
    (is (= "commissioned" (get-in s1 ["cell_state" "phase"])))
    (is (= true (get-in s1 ["cell_state" "freq_restored"])))
    (is (= "dispatch_committed" (get-in s2 ["cell_state" "phase"])))
    (is (= false (get dispatch "serverHeldKey")))
    (is (= true (get dispatch "dryRun")))
    (is (= true (get dispatch "witnessOk")))))

(deftest test-grid-edge-non-civilian-use-raises
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/transition-commission {"use" "weapon" "load_step_kw" 120.0}))))

(deftest test-grid-edge-server-signature-refused
  (let [s1 (-> (sm/transition-commission {"load_step_kw" 120.0})
               (assoc "member_sig" "m:sig" "server_sig" "s:sig" "witness_sigs" WITNESS))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sm/transition-commit-dispatch s1)))))

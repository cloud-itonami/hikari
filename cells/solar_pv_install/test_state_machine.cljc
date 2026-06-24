(ns hikari.cells.solar-pv-install.test-state-machine
  "Tests for the hikari solar_pv_install gated cell state machine (ADR-2605261100 port).
  1:1 port of the solar_pv_install cases in cells/test_state_machines.py (pytest →
  clojure.test): plan → commit happy path (reachable, member-signed, dry-run), the
  unreachable-target commit block, and the G8 witness-quorum commit block."
  (:require [clojure.test :refer [deftest is]]
            [hikari.cells.solar-pv-install.state-machine :as sm]))

(def WITNESS ["did:web:etzhayyim.com:kuniumi:robot:otete-01"
              "did:web:etzhayyim.com:kuniumi:robot:mimi-01"])

(deftest test-install-happy-path-commits-dry-run-job
  (let [s1 (sm/transition-plan-motion
            {"target_x" 1.5 "target_y" 0.4 "member_sig" "m:sig" "witness_sigs" WITNESS})
        s2 (sm/transition-commit-job s1)]
    (is (= "motion_planned" (get-in s1 ["cell_state" "phase"])))
    (is (= "job_committed" (get-in s2 ["cell_state" "phase"])))
    (is (= true (get-in s2 ["cell_state" "payload" "job" "dryRun"])))))

(deftest test-install-unreachable-target-blocks-commit
  (let [s1 (sm/transition-plan-motion
            {"target_x" 99.0 "target_y" 0.0 "member_sig" "m:sig" "witness_sigs" WITNESS})]
    (is (thrown? clojure.lang.ExceptionInfo (sm/transition-commit-job s1)))))

(deftest test-install-witness-below-quorum-blocks-commit
  (let [s1 (sm/transition-plan-motion
            {"target_x" 1.5 "target_y" 0.4 "member_sig" "m:sig" "witness_sigs" ["did:r:a"]})]
    (is (thrown? clojure.lang.ExceptionInfo (sm/transition-commit-job s1)))))

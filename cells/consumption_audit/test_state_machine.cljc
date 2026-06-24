(ns hikari.cells.consumption-audit.test-state-machine
  "Tests for hikari consumption_audit state machine (ADR-2605261100).
  R0 scaffold: verifies the cell raises correctly before activation."
  (:require [clojure.test :refer [deftest is]]
            [hikari.cells.consumption-audit.state-machine :as sm]))

(deftest solve-raises-r0-scaffold
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"hikari R0 scaffold"
       (sm/solve {}))))

(deftest solve-raises-with-correct-actor-metadata
  (try
    (sm/solve {"projectId" "TEST"})
    (is false "expected exception")
    (catch clojure.lang.ExceptionInfo e
      (is (= :hikari (:actor (ex-data e))))
      (is (= :consumption-audit (:cell (ex-data e))))
      (is (= :r0-scaffold (:status (ex-data e)))))))

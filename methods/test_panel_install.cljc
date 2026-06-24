(ns hikari.methods.test-panel-install
  "hikari panel-install robot-motion tests (ADR-2606091800). 1:1 Clojure port of
  methods/test_panel_install.py, with the structural gates test-enforced:

    IK / reachability — a reachable target plans a clean motion (joints solved,
                        envelope ok, witness ok, keyless, dry-run); an unreachable
                        target reports not-reachable with no joints and 0 steps.
    N1 civilian       — a non-civilian use RAISES a SafetyError.
    G15/G7 no-server-key — a server signature RAISES; a missing member signature RAISES.
    G8 witness quorum — a sub-quorum is RECORDED (witness-ok false), NOT raised.
    safety envelope   — a fast move fine far from humans VIOLATES the slow ceiling when
                        a person may be present.
    aggregate/dry     — to-datoms is keyless + dry-run.

  cell/state-machine tests are deferred (Council-gated at R0)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [hikari.methods.substrate :as sub]
            [hikari.methods.panel-install :as pi]))

(def WITNESS ["did:web:etzhayyim.com:kuniumi:robot:otete-01"
              "did:web:etzhayyim.com:kuniumi:robot:mimi-01"])

;; ── ported assertions ───────────────────────────────────────────────────
(deftest test-reachable-target-plans-clean-motion
  (let [plan (pi/plan-panel-install [1.5 0.4] "m:ed25519:demo" WITNESS)]
    (is (:reachable plan))
    (is (some? (:joints-goal plan)))
    (is (:envelope-ok plan))
    (is (:witness-ok plan))
    (is (false? (:server-held-key plan)))
    (is (true? (:dry-run plan)))))

(deftest test-unreachable-target-reports-not-reachable
  (let [far [(+ (sub/max-reach pi/OTETE-ARM) 1.0) 0.0]
        plan (pi/plan-panel-install far "m:sig" WITNESS)]
    (is (false? (:reachable plan)))
    (is (nil? (:joints-goal plan)))
    (is (= 0 (:trajectory-steps plan)))))

(deftest test-non-civilian-use-refused
  (doseq [use ["weapon" "interdiction" "smelting"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"N1"
                          (pi/plan-panel-install [1.0 0.2] "m:sig" WITNESS :use use))
        (str "use " use " must be refused"))
    (is (sub/safety-error?
         (try (pi/plan-panel-install [1.0 0.2] "m:sig" WITNESS :use use) nil
              (catch clojure.lang.ExceptionInfo e e))))))

(deftest test-server-signature-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"G15/G7"
                        (pi/plan-panel-install [1.0 0.2] "m:sig" WITNESS :server-sig "s:sig"))))

(deftest test-missing-member-signature-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"G15/G7"
                        (pi/plan-panel-install [1.0 0.2] "" WITNESS))))

(deftest test-witness-quorum-below-two-recorded-not-raised
  (let [plan (pi/plan-panel-install [1.2 0.3] "m:sig" ["did:r:a"])]
    (is (false? (:witness-ok plan))))) ; escalation Datom, not a hard raise

(deftest test-human-proximity-forces-slower-envelope
  ;; A fast 15-step move fine far from humans violates the slow ceiling near a person.
  (let [target [1.8 0.6]
        fast (pi/plan-panel-install target "m:sig" WITNESS :human-present false :steps 15)
        slow-ceiling (pi/plan-panel-install target "m:sig" WITNESS :human-present true :steps 15)]
    (is (true? (:envelope-ok fast)))
    (is (false? (:envelope-ok slow-ceiling)))
    (is (seq (:envelope-violations slow-ceiling)))))

(deftest test-datoms-dry-run-and-keyless
  (let [plan (pi/plan-panel-install [1.5 0.4] "m:sig" WITNESS)
        d (pi/to-datoms plan "install-001")]
    (is (false? (get d ":install/server-held-key")))
    (is (true? (get d ":install/dry-run")))
    (is (true? (get d ":install/reachable")))))

(ns hikari.methods.test-microgrid
  "hikari microgrid operational-loop tests (ADR-2606091800). 1:1 Clojure port of
  methods/test_microgrid.py, PLUS the constitutional gate made explicit + test-enforced:

    50 Hz restore — freq-restored + final-freq ≈ 50 Hz + generation tracks load (both
                    directions: a +40 kW step AND a load-shed below the 100 kW base).
    ROCOF latch   — a normal load step does NOT trip; an islanding-scale step DOES.
    N1 civilian   — a non-civilian use (weapon/fire-control/mining) RAISES a SafetyError.
    aggregate/dry — to-datoms is aggregate-only + dry-run + representative.

  cell/state-machine/commissioning-harness tests are deferred (those need unported
  sibling modules + the kuni-umi commissioning seam, Council-gated at R0)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [hikari.methods.substrate :as sub]
            [hikari.methods.microgrid :as mg]))

(defn approx?
  "pytest.approx(target, abs=tol)."
  [actual target tol]
  (<= (Math/abs (- (double actual) (double target))) tol))

;; ── ported assertions ───────────────────────────────────────────────────
(deftest test-microgrid-restores-frequency-after-load-step
  (let [res (mg/commission-microgrid 140.0)]
    (is (:freq-restored res))
    (is (approx? (:final-freq-hz res) 50.0 2e-2))
    (is (approx? (:final-generation-kw res) 140.0 1.0)) ; gen tracks load
    (is (<= 0.0 (:final-soc res) 1.0))
    (is (> (:settling-seconds res) 0))))

(deftest test-microgrid-handles-load-shed-direction
  ;; A load drop (below the 100 kW base) is also rejected back to 50 Hz.
  (let [res (mg/commission-microgrid 60.0)]
    (is (:freq-restored res))
    (is (approx? (:final-generation-kw res) 60.0 1.0))))

(deftest test-non-civilian-use-refused
  (doseq [use ["weapon" "fire-control" "mining"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"N1"
                          (mg/commission-microgrid 120.0 :use use))
        (str "use " use " must be refused"))
    ;; and it is specifically a SafetyError (matches Python `except SafetyError`).
    (is (sub/safety-error?
         (try (mg/commission-microgrid 120.0 :use use) nil
              (catch clojure.lang.ExceptionInfo e e))))))

(deftest test-normal-load-step-does-not-trip-rocof
  ;; +60 kW step: primary droop arrests the dive, ROCOF stays under the trip.
  (let [res (mg/commission-microgrid 160.0)]
    (is (>= (:rocof-max-hz-per-s res) 0.0))
    (is (false? (:rocof-tripped res)))))

(deftest test-islanding-scale-step-trips-rocof
  ;; +80 kW (near-doubling) is an islanding-scale transient: the guard trips.
  (let [res (mg/commission-microgrid 180.0)]
    (is (true? (:rocof-tripped res)))
    (is (:freq-restored res)))) ; still recovers in sim, but the relay flags it

(deftest test-rocof-helper-detects-fast-transient
  (let [fast [[0.0 50.0 0.0] [0.01 47.0 0.0]]] ; 3 Hz in 10 ms = 300 Hz/s
    (is (approx? (mg/rocof fast 0.01) 300.0 1e-6))))

(deftest test-datoms-are-aggregate-and-dry-run
  (let [res (mg/commission-microgrid 140.0)
        d (mg/to-datoms res "microgrid-001")]
    (is (true? (get d ":microgrid/dry-run")))
    (is (true? (get d ":microgrid/representative")))
    (is (true? (get d ":microgrid/freq-restored")))))

#!/usr/bin/env bb
;; hikari 光 — ANALYTICAL physics validation of the microgrid swing equation.
;; Run:  bb --classpath 20-actors 20-actors/hikari/methods/test_microgrid_physics.cljc
(ns hikari.methods.test-microgrid-physics
  "Analytical physics validation of the microgrid swing equation — distinct from test_microgrid,
  which only smoke-tests OUTCOMES (does the frequency restore? does the ROCOF relay trip?). This
  pins the simulator against the closed-form electromechanical physics it claims to implement:

    swing equation   2H·df/dt = ΔP_pu·f_nom − D·(f − f_nom)
    initial ROCOF    df/dt|_(f=f_nom) = ΔP_pu·f_nom / (2H)
    droop steady     Δf_ss = ΔP_pu·f_nom / D            (primary response only, no secondary PI)

  so a regression in the inertia constant H, damping D, per-unit base, the Euler integrator, or
  the ROCOF-relay windowing is caught as a NUMBER, not just 'it still settles somewhere'."
  (:require [hikari.methods.microgrid :as mg]
            [clojure.test :refer [deftest is run-tests]]))

(defn- close? [a b eps] (< (Math/abs (- (double a) (double b))) (double eps)))
(def ^:private base {:f-nom 50.0 :inertia-h 4.0 :damping-d 1.5 :s-base 200.0 :p-load 100.0 :f 50.0})
(defn- plant [opts] (mg/->microgrid-plant (merge base opts)))

(deftest initial-rocof-matches-swing-equation
  ;; at f = f_nom the damping term vanishes, so the instantaneous ROCOF right after a load step
  ;; ΔP is exactly df/dt = ΔP_pu·f_nom/(2H). A load INCREASE → negative ROCOF (frequency dives).
  (doseq [[dload h] [[60.0 4.0] [-40.0 4.0] [60.0 8.0] [30.0 2.0]]]
    (let [p (plant {:inertia-h h})
          dt 1.0e-4
          _ (mg/plant-set-load! p (+ 100.0 dload))
          f0 (mg/plant-measure p)
          _ (mg/plant-step! p 100.0 dt)
          sim (/ (- (mg/plant-measure p) f0) dt)
          analytic (/ (* (/ (- dload) 200.0) 50.0) (* 2.0 h))]
      (is (close? sim analytic 1e-6)
          (str "ROCOF ΔP=" dload " H=" h ": sim " sim " vs ΔP_pu·f_nom/2H " analytic)))))

(deftest primary-droop-steady-state-matches-damping
  ;; with only the inertial/damping primary response (gen held at p-base, no secondary PI), the
  ;; frequency settles where df/dt = 0 → Δf_ss = ΔP_pu·f_nom/D.
  (doseq [[dload d] [[60.0 1.5] [-40.0 1.5] [60.0 3.0]]]
    (let [p (plant {:damping-d d :p-load (+ 100.0 dload)})]
      (dotimes [_ 60000] (mg/plant-step! p 100.0 0.01))   ;; 600 s sim ≫ τ≈2H/D, fully settled
      (let [f-ss (mg/plant-measure p)
            analytic (+ 50.0 (/ (* (/ (- dload) 200.0) 50.0) d))]
        (is (close? f-ss analytic 1e-4)
            (str "droop steady-state ΔP=" dload " D=" d ": sim " f-ss " vs " analytic))))))

(deftest load-step-sign-is-energy-consistent
  ;; energy balance: a load INCREASE drives frequency DOWN, a load SHED drives it UP
  (let [up (plant {}) dn (plant {})]
    (mg/plant-set-load! up 160.0) (mg/plant-step! up 100.0 0.01)
    (mg/plant-set-load! dn 60.0)  (mg/plant-step! dn 100.0 0.01)
    (is (< (mg/plant-measure up) 50.0) "load increase → frequency dives")
    (is (> (mg/plant-measure dn) 50.0) "load shed → frequency rises")))

(deftest rocof-relay-measures-trajectory-slope
  ;; the ROCOF relay reports max |df/dt| over its window; on a linear ramp it equals |slope|
  ;; (the windowing must not under-/over-report a constant rate)
  (let [slope -1.875
        traj (mapv (fn [i] (let [t (* i 0.01)] [t (+ 50.0 (* slope t)) 100.0])) (range 0 50))]
    (is (close? (mg/rocof traj 0.1) (Math/abs slope) 1e-6)
        "rocof of a constant-slope ramp = |slope|")))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (let [{:keys [fail error]} (run-tests 'hikari.methods.test-microgrid-physics)]
       (System/exit (if (zero? (+ fail error)) 0 1)))))

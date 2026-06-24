#!/usr/bin/env bb
;; hikari 光 — tests for the analytical initial RoCoF (grid-inertia response).
;; Run:  bb --classpath 20-actors 20-actors/hikari/methods/test_initial_rocof.cljc
(ns hikari.methods.test-initial-rocof
  "Tests for initial-rocof — the swing equation's t=0 |df/dt| = |ΔP_pu|·f_nom/(2H), the grid-inertia
  response. Pins the analytical value against the plant model's realized first-step slope, plus the
  inertia-adequacy scaling and the anti-islanding trip comparison."
  (:require [hikari.methods.microgrid :as m]
            [clojure.test :refer [deftest is run-tests]]))

(defn- close?
  ([x y] (close? x y 1e-9))
  ([x y tol] (< (Math/abs (- (double x) (double y))) tol)))

(def ^:private plant {:f-nom 50.0 :inertia-h 4.0})

(deftest is-the-swing-equation-t0-value
  (is (close? 0.625 (m/initial-rocof plant 0.1)) "0.1 pu loss on a 50 Hz grid, H=4 → 0.1·50/8 = 0.625 Hz/s")
  (is (close? 0.625 (m/initial-rocof plant -0.1)) "magnitude only — sign-independent"))

(deftest matches-the-plant-models-realized-t0-slope
  ;; one plant-step! from nominal with command 0: the realized |df/dt| equals the analytical value
  ;; for the resulting imbalance (-p_load/s_base). At f = f_nom the damping term is zero → exact.
  (let [s-base 200.0 p-load 20.0 dt 0.001
        grid (m/->microgrid-plant {:f-nom 50.0 :inertia-h 4.0 :s-base s-base :p-load 0.0 :f 50.0})]
    (m/plant-set-load! grid p-load)
    (m/plant-step! grid 0.0 dt)
    (let [realized   (/ (Math/abs (- (m/plant-measure grid) 50.0)) dt)
          analytical (m/initial-rocof plant (/ p-load s-base))]
      (is (close? realized analytical 1e-6) "initial-rocof = the plant model's realized t=0 |df/dt|"))))

(deftest inertia-adequacy-and-linear-scaling
  (is (< (m/initial-rocof {:f-nom 50.0 :inertia-h 8.0} 0.1)
         (m/initial-rocof {:f-nom 50.0 :inertia-h 4.0} 0.1))
      "doubling inertia H halves the RoCoF — the inertia-adequacy relation")
  (is (close? (* 2.0 (m/initial-rocof plant 0.1)) (m/initial-rocof plant 0.2))
      "RoCoF scales linearly with the imbalance"))

(deftest a-large-imbalance-exceeds-the-anti-islanding-trip
  (is (> (m/initial-rocof plant 0.4) m/ROCOF-TRIP-HZ-PER-S)
      "a 0.4 pu step (2.5 Hz/s) exceeds the 2.0 Hz/s ROCOF trip — the relay would act"))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (let [{:keys [fail error]} (run-tests 'hikari.methods.test-initial-rocof)]
       (System/exit (if (zero? (+ fail error)) 0 1)))))

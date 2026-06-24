#!/usr/bin/env bb
;; hikari 光 — kinematics validation of the shared kuni-umi planar-arm FK/IK.
;; Run:  bb --classpath 20-actors 20-actors/hikari/methods/test_substrate_kinematics.cljc
(ns hikari.methods.test-substrate-kinematics
  "Kinematics validation of the planar-arm forward/inverse kinematics in the shared kuni-umi
  substrate — the closed-form 2-link IK (`ik2`) that drives panel_install and any robot-arm reach
  task across the infra-robotics fleet (hikari/mizuho/kamado/noroshi share this substrate). The
  existing tests exercise panel_install end-to-end but never pin the FUNDAMENTAL kinematics
  invariant: **FK(IK(target)) = target**. A regression in the cosine-rule IK, the atan2 branch, or
  the elbow-mirror selection would still produce *some* joint angles and pass the integration
  tests, while silently sending the arm to the wrong point. This validates the analytic IK against
  forward kinematics + the reachability geometry."
  (:require [hikari.methods.substrate :as sub]
            [clojure.test :refer [deftest is run-tests]]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-8))   ; fk rounds to 9 dp

(deftest fk-ik-roundtrip-over-the-workspace-both-elbows
  ;; for every reachable target on a grid, BOTH mirror IK solutions land the end-effector on it
  (doseq [links [[0.5 0.4] [0.3 0.3] [0.6 0.25]]]
    (let [arm (sub/->planar-arm links)]
      (doseq [xi (range -8 9) yi (range -8 9)
              :let [x (* 0.1 xi) y (* 0.1 yi)]
              :when (sub/reachable arm x y)
              elbow [true false]]
        (let [p (sub/fk arm (sub/ik2 arm x y elbow))]
          (is (and (close? (:x p) x) (close? (:y p) y))
              (str "FK∘IK ≠ target for links " links " target (" x "," y ") elbow=" elbow
                   " → (" (:x p) "," (:y p) ")")))))))

(deftest reachability-bounds-match-link-geometry
  (let [arm (sub/->planar-arm [0.5 0.4])]
    (is (close? (sub/max-reach arm) 0.9) "max reach = Σ link lengths (arm fully extended)")
    (is (close? (sub/min-reach arm) 0.1) "min reach = |L1 − L2| (arm fully folded)")
    (is (sub/reachable arm 0.6 0.0))
    (is (some? (sub/ik2 arm 0.6 0.0)) "a reachable target yields joint angles")
    (is (not (sub/reachable arm 1.5 0.0)) "beyond max reach")
    (is (nil? (sub/ik2 arm 1.5 0.0)) "an unreachable (too-far) target → nil IK")
    (is (not (sub/reachable arm 0.02 0.0)) "inside the dead zone (< min reach)")))

(deftest fk-at-known-configurations
  (let [arm (sub/->planar-arm [0.5 0.4])]
    ;; straight arm (all joints 0) → fully extended along +x
    (let [p (sub/fk arm [0.0 0.0])]
      (is (close? (:x p) 0.9)) (is (close? (:y p) 0.0)) (is (close? (:theta p) 0.0)))
    ;; base joint at +90° → the (still-straight) arm points along +y at the same reach
    (let [p (sub/fk arm [(/ Math/PI 2) 0.0])]
      (is (close? (:x p) 0.0)) (is (close? (:y p) 0.9)))))

(deftest elbow-solutions-are-distinct-mirrors-to-the-same-point
  (let [arm (sub/->planar-arm [0.5 0.4])
        up (sub/ik2 arm 0.6 0.2 true)
        dn (sub/ik2 arm 0.6 0.2 false)]
    (is (not= up dn) "elbow-up and elbow-down are distinct joint configurations")
    (let [pu (sub/fk arm up) pd (sub/fk arm dn)]
      (is (and (close? (:x pu) (:x pd)) (close? (:y pu) (:y pd)))
          "both elbow solutions reach the same end-effector"))))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (let [{:keys [fail error]} (run-tests 'hikari.methods.test-substrate-kinematics)]
       (System/exit (if (zero? (+ fail error)) 0 1)))))

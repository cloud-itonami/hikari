(ns hikari.methods.panel-install
  "panel_install — hikari solar_pv_install robot motion loop (R0 :representative).
  1:1 Clojure port of methods/panel_install.py (ADR-2605261100 / 2606091800).

  Plans an Otete arm motion that places a PV panel at a target pose and refuses to
  dispatch unless every structural gate holds:

    N1     civilian-use only (assert-civilian)        install / service / inspect / clean
    G15/G7 no-server-key (require-member-signature)    member signs, platform never
    G8     witness quorum >=2 independent robot DIDs    kuni-umi constitutional
    safety per-step joint-rate ceiling, slower whenever a person may be in the cell
    G2 (kuni-umi)  motion stays a planned trajectory; never actuates — cell.py .solve()
                   is Council-gated (R0 dry-run only).

  Reachability + IK come from the substrate PlanarArm (kami-genesis stand-in).

  House style: Python ':…' keys stay literal strings; kebab keyword keys on records;
  pure fns; gates RAISE (ex-info) fail-fast in the SAME order as Python. IK angles are
  byte-identical (Math/atan2/sin/cos/sqrt last-ULP + HALF_EVEN round to 9 dp)."
  (:require [hikari.methods.substrate :as sub]))

(def PERMITTED-USES ["install" "service" "inspect" "clean"])

;; Otete arm :representative geometry — a 2-link planar reach model (metres).
(def OTETE-ARM (sub/->planar-arm [1.2 1.0]))

(defn ->panel-install-plan
  "Frozen PanelInstallPlan ≅ Python dataclass. Kebab keyword keys."
  [m]
  (select-keys m [:use :target-xy :reachable :joints-goal :trajectory-steps
                  :envelope-ok :envelope-violations :human-present :member-sig
                  :witness-ok :server-held-key :dry-run]))

(defn plan-panel-install
  "Plan an install motion. RAISES before planning if a structural gate fails.
  Gate order is fail-fast: civilian use, then no-server-key, then witness quorum.
  Only after the gates pass do we solve IK and check the trajectory envelope. A
  witness-quorum miss does NOT raise (Council-escalation Datom), so the plan is
  returned with :witness-ok false for the audit trail."
  [target-xy member-sig witness-sigs
   & {:keys [q-start use human-present steps dt server-sig]
      :or {q-start [0.0 0.0] use "install" human-present false
           steps 60 dt 0.1 server-sig ""}}]
  (sub/assert-civilian use PERMITTED-USES)                ; N1
  (sub/require-member-signature member-sig server-sig)    ; G15/G7
  (let [quorum (sub/witness-quorum-ok witness-sigs)       ; G8 (record, do not raise)
        [x y] target-xy
        reachable (sub/reachable OTETE-ARM x y)
        joints-goal (when reachable (sub/ik2 OTETE-ARM x y true))
        env (sub/->safety-envelope {:max-joint-speed 1.0 :human-proximity-speed 0.25
                                    :max-reach (sub/max-reach OTETE-ARM)})]
    (let [[traj envelope-ok violations]
          (if (some? joints-goal)
            (let [traj (sub/joint-trajectory q-start joints-goal steps)
                  check (sub/check-trajectory env traj dt human-present)]
              [traj (:ok check) (:violations check)])
            [[] false []])]
      (->panel-install-plan
       {:use use
        :target-xy target-xy
        :reachable reachable
        :joints-goal joints-goal
        :trajectory-steps (count traj)
        :envelope-ok envelope-ok
        :envelope-violations violations
        :human-present human-present
        :member-sig member-sig
        :witness-ok (:ok quorum)
        :server-held-key false   ; G15: structural invariant
        :dry-run true}))))       ; G10: R0 offline only

(defn to-datoms
  "Project an install plan into kotoba EAVT-shaped datoms (G6). Python ':…' attr names
  stay literal string keys."
  ([plan job-id] (to-datoms plan job-id "otete-01"))
  ([plan job-id robot-id]
   {":install/id" job-id
    ":install/robot" robot-id
    ":install/use" (:use plan)
    ":install/target-x" (nth (:target-xy plan) 0)
    ":install/target-y" (nth (:target-xy plan) 1)
    ":install/reachable" (:reachable plan)
    ":install/trajectory-steps" (:trajectory-steps plan)
    ":install/envelope-ok" (:envelope-ok plan)
    ":install/human-present" (:human-present plan)
    ":install/member-sig" (:member-sig plan)
    ":install/witness-ok" (:witness-ok plan)
    ":install/server-held-key" (:server-held-key plan) ; G15: always false
    ":install/dry-run" (:dry-run plan)}))               ; G10

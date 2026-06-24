(ns hikari.cells.solar-pv-install.state-machine
  "1:1 port of cells/solar_pv_install/state_machine.py — Otete panel-install motion
  (gated transitions). Pure, deterministic transitions enforcing hikari gates. The
  runnable motion planner lives in the SIBLING REAL port hikari.methods.panel-install;
  this wires it into a phase machine ending at a member-signed, dry-run install record
  (G7/G8/G10). cell.py .solve() stays Council-gated.

  InstallState dataclass → string-keyed map under \"cell_state\" (all fields present,
  defaults supplied). Override inputs (use / target_x / target_y / human_present /
  member_sig / server_sig / witness_sigs) are read from the TOP-LEVEL state map,
  mirroring the Python `state.get(k, cs.k)` reads."
  (:require [hikari.methods.panel-install :as pi]))

(defn- install-state
  "InstallState defaults merged with any existing \"cell_state\" map (string keys)."
  [state]
  (merge {"phase" "init"
          "job_id" "install-01"
          "robot_id" "otete-01"
          "use" "install"
          "target_x" 1.5
          "target_y" 0.4
          "human_present" false
          "member_sig" ""
          "server_sig" ""
          "witness_sigs" []
          "reachable" false
          "envelope_ok" false
          "payload" {}}
         (get state "cell_state" {})))

(defn transition-plan-motion
  "Plan the install motion (raises on non-civilian use / server key / no member sig)."
  [state]
  (let [cs (install-state state)
        use (get state "use" (get cs "use"))
        target-x (double (get state "target_x" (get cs "target_x")))
        target-y (double (get state "target_y" (get cs "target_y")))
        human-present (boolean (get state "human_present" (get cs "human_present")))
        member-sig (get state "member_sig" (get cs "member_sig"))
        server-sig (get state "server_sig" (get cs "server_sig"))
        witness-sigs (get state "witness_sigs" (get cs "witness_sigs"))
        plan (pi/plan-panel-install [target-x target-y] member-sig witness-sigs
                                    :use use :human-present human-present :server-sig server-sig)
        cs (assoc cs
                  "use" use
                  "target_x" target-x
                  "target_y" target-y
                  "human_present" human-present
                  "member_sig" member-sig
                  "server_sig" server-sig
                  "witness_sigs" witness-sigs
                  "reachable" (:reachable plan)
                  "envelope_ok" (:envelope-ok plan)
                  "payload" (assoc (get cs "payload")
                                   "plan" (pi/to-datoms plan (get cs "job_id") (get cs "robot_id"))
                                   "_witness_ok" (:witness-ok plan))
                  "phase" "motion_planned")]
    {"cell_state" cs "next_node" "commit_job"}))

(defn transition-commit-job
  "Commit a dry-run install job only if reachable + envelope-safe + quorum met."
  [state]
  (let [cs (install-state state)]
    (when-not (get cs "reachable")
      (throw (ex-info "target unreachable: cannot commit install job" {:type ::unreachable})))
    (when-not (get cs "envelope_ok")
      (throw (ex-info "trajectory violates safety envelope: cannot commit install job"
                      {:type ::envelope-violation})))
    (when-not (get-in cs ["payload" "_witness_ok"])
      (throw (ex-info "witness quorum < 2 (G8): cannot commit install job"
                      {:type ::witness-quorum})))
    (let [cs (assoc cs
                    "payload" (assoc (get cs "payload")
                                     "job" (assoc (get-in cs ["payload" "plan"])
                                                  "committed" true "dryRun" true))
                    "phase" "job_committed")]
      {"cell_state" cs "next_node" "end"})))

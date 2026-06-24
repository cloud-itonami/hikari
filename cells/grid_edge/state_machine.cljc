(ns hikari.cells.grid-edge.state-machine
  "1:1 port of cells/grid_edge/state_machine.py — microgrid commissioning + dispatch
  (gated transitions). Pure, deterministic transitions enforcing hikari gates. The
  runnable control loop lives in the SIBLING REAL port hikari.methods.microgrid; this
  wires it into a phase machine that ends at a member-signed, dry-run dispatch record
  (G7/G8/G10). cell.py .solve() stays Council-gated — these transitions are exercised
  by tests, not live actuation.

  GridState dataclass → string-keyed map under \"cell_state\" (all fields present,
  defaults supplied). Override inputs (use / load_step_kw / member_sig / server_sig /
  witness_sigs) are read from the TOP-LEVEL state map, mirroring the Python
  `state.get(k, cs.k)` reads, so a caller threads inputs by assoc'ing them on the map
  returned by the previous transition."
  (:require [hikari.methods.microgrid :as mg]
            [hikari.methods.substrate :as sub]))

(defn- grid-state
  "GridState defaults merged with any existing \"cell_state\" map (string keys)."
  [state]
  (merge {"phase" "init"
          "microgrid_id" "microgrid-01"
          "use" "grid-control"
          "load_step_kw" 140.0
          "freq_restored" false
          "rocof_tripped" false
          "member_sig" ""
          "server_sig" ""
          "witness_sigs" []
          "payload" {}}
         (get state "cell_state" {})))

(defn transition-commission
  "Run the microgrid acceptance test (raises if use is non-civilian, N1)."
  [state]
  (let [cs (grid-state state)
        use (get state "use" (get cs "use"))
        load-step-kw (double (get state "load_step_kw" (get cs "load_step_kw")))
        result (mg/commission-microgrid load-step-kw :use use)
        cs (assoc cs
                  "use" use
                  "load_step_kw" load-step-kw
                  "freq_restored" (:freq-restored result)
                  "rocof_tripped" (:rocof-tripped result)
                  "payload" (assoc (get cs "payload")
                                   "commissioning" (mg/to-datoms result (get cs "microgrid_id")))
                  "phase" "commissioned")]
    {"cell_state" cs "next_node" "commit_dispatch"}))

(defn transition-commit-dispatch
  "G7/G15 member-signed dispatch + G8 witness quorum; always dry-run at R0."
  [state]
  (let [cs (grid-state state)
        member-sig (get state "member_sig" (get cs "member_sig"))
        server-sig (get state "server_sig" (get cs "server_sig"))
        witness-sigs (get state "witness_sigs" (get cs "witness_sigs"))]
    (sub/require-member-signature member-sig server-sig)   ; raises on violation
    (let [quorum (sub/witness-quorum-ok witness-sigs)]
      (when-not (get cs "freq_restored")
        (throw (ex-info "acceptance test failed: frequency not restored; cannot commission"
                        {:type ::commission-failed})))
      (let [cs (assoc cs
                      "member_sig" member-sig
                      "server_sig" server-sig
                      "witness_sigs" witness-sigs
                      "payload" (assoc (get cs "payload")
                                       "dispatch"
                                       {"microgridId" (get cs "microgrid_id")
                                        "use" (get cs "use")
                                        "freqRestored" (get cs "freq_restored")
                                        "rocofTripped" (get cs "rocof_tripped")
                                        "memberSig" member-sig
                                        "witnessOk" (:ok quorum)
                                        "escalateCouncilLv6" (boolean (:escalate-council-lv6 quorum))
                                        "serverHeldKey" false   ; G15 structural invariant
                                        "dryRun" true})         ; G10: R0 offline only
                      "phase" "dispatch_committed")]
        {"cell_state" cs "next_node" "end"}))))

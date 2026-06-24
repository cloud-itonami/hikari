(ns hikari.methods.microgrid
  "microgrid — hikari grid_edge operational control loop (R0 :representative).
  1:1 Clojure port of methods/microgrid.py (ADR-2605261100 / 2606091800).

  The runnable, tested core behind the `grid_edge` cell. It proves the islanded
  microgrid actually stabilises: a load step knocks the bus frequency down, the
  droop + secondary-PI loop drives dispatchable generation until the frequency error
  integrates back to zero, and an anti-islanding ROCOF guard trips on an abnormal
  rate-of-change of frequency.

  Floating-point twin of the open-ot field-tier cells (DROOP_P_F / ANTI_ISLANDING_ROCOF
  / SOC_KALMAN, Rust WASM). hikari constitutional gates apply:
    G4/G5 — no nuclear/fossil/rare-earth source ever enters the dispatch model (the
            dispatch command is just kW; the *source* is enforced upstream by the
            sourcing audit, never represented as fossil here),
    G6    — inference stays Murakumo (not used in this deterministic loop),
    G10   — live grid control is consent-gated; this module is offline sim only and
            cell.py .solve() stays Council-gated,
    N1    — civilian-use allowlist enforced via substrate/assert-civilian.

  House style: Python ':…' keys stay literal strings; kebab keyword keys on records;
  pure fns; mutable plant state mirrors the Python object so the swing-equation
  integration order is byte-identical. round()/{:.Nf} = HALF_EVEN on the exact double."
  (:require [hikari.methods.substrate :as sub]))

;; hikari grid civilian-use allowlist (closed-world, N1). A "source" is never an enum
;; here; fossil/nuclear are excluded by sourcing audit upstream (G4/G5).
(def PERMITTED-USES ["grid-control" "island" "black-start" "dispatch" "load-shed"])

;; Anti-islanding: trip if |df/dt| exceeds this (Hz/s). Mirrors open-ot ROCOF cell.
(def ROCOF-TRIP-HZ-PER-S 2.0)

;; ── MicrogridPlant (mutable swing-equation plant) ────────────────────────────
;; Mirrors plant.MicrogridPlant: the controlled quantity is bus frequency (Hz);
;; frequency moves with the active-power imbalance; net generation charges/discharges
;; the battery SoC. State is held in an atom so the simulate loop mutates it in place,
;; exactly as the Python dataclass instance is mutated.
(defn ->microgrid-plant
  [{:keys [f-nom inertia-h damping-d s-base p-load battery-kwh soc f]
    :or {f-nom 50.0 inertia-h 4.0 damping-d 1.5 s-base 200.0 p-load 100.0
         battery-kwh 500.0 soc 0.6 f 50.0}}]
  (atom {:f-nom f-nom :inertia-h inertia-h :damping-d damping-d :s-base s-base
         :p-load p-load :battery-kwh battery-kwh :soc soc :f f}))

(defn plant-measure [plant] (:f @plant))

(defn plant-set-load!
  "Apply a load step (the disturbance the controller must reject)."
  [plant p-load-kw]
  (swap! plant assoc :p-load p-load-kw)
  nil)

(defn plant-step!
  "Advance the plant by dt seconds under dispatchable-generation `command` (kW).
  2H·df/dt = (P_gen−P_load)/S_base·f_nom − D·(f−f_nom); SoC tracks net energy."
  [plant command dt]
  (swap! plant
         (fn [{:keys [f-nom inertia-h damping-d s-base p-load battery-kwh soc f] :as st}]
           (let [imbalance-pu (/ (- command p-load) s-base)
                 dfdt (/ (- (* imbalance-pu f-nom) (* damping-d (- f f-nom)))
                         (* 2.0 inertia-h))
                 f' (+ f (* dfdt dt))
                 net-kwh (* (- command p-load) (/ dt 3600.0))
                 soc' (min 1.0 (max 0.0 (+ soc (/ net-kwh battery-kwh))))]
             (assoc st :f f' :soc soc'))))
  nil)

;; ── rocof ─────────────────────────────────────────────────────────────────
(defn rocof
  "Max |df/dt| over a frequency trajectory, measured over a `window-s` window.
  Each trajectory sample is [t f cmd]. Mirrors a real ROCOF relay (averages over
  ~100 ms, not a single sample). span = max(1, round(window/dt_sample))."
  ([trajectory] (rocof trajectory 0.1))
  ([trajectory window-s]
   (let [traj (vec trajectory)
         n (count traj)]
     (if (< n 2)
       0.0
       (let [dt-sample (- (first (nth traj 1)) (first (nth traj 0)))
             span (if (> dt-sample 0)
                    (max 1 (long (sub/py-round (/ window-s dt-sample) 0)))
                    1)]
         (loop [i span, worst 0.0]
           (if (>= i n)
             worst
             (let [[t0 f0 _] (nth traj (- i span))
                   [t1 f1 _] (nth traj i)
                   dt (- t1 t0)]
               (recur (inc i)
                      (if (> dt 0)
                        (max worst (/ (Math/abs (- f1 f0)) dt))
                        worst))))))))))

(defn initial-rocof
  "The instantaneous |df/dt| (Hz/s) the moment a per-unit power imbalance appears, BEFORE damping or
  droop control respond — the swing equation's t=0 slope |ΔP_pu|·f_nom/(2·H), the same term
  `plant-step!` integrates (at f = f_nom the damping term is zero, so the first step is pure
  inertia). This is the grid-INERTIA response: how fast frequency falls when generation is suddenly
  lost, and why more inertia H (or a smaller imbalance) buys a gentler, more stable fall — the
  inertia-adequacy reading a `rocof` relay later measures on the trajectory. A descriptive physics
  quantity. Takes a plant spec (`:f-nom`, `:inertia-h`) + the per-unit imbalance."
  [{:keys [f-nom inertia-h] :or {f-nom 50.0 inertia-h 4.0}} imbalance-pu]
  (Math/abs (/ (* (double imbalance-pu) f-nom) (* 2.0 inertia-h))))

;; ── CommissioningResult ─────────────────────────────────────────────────────
(defn ->commissioning-result
  "Frozen CommissioningResult ≅ Python dataclass. Kebab keyword keys."
  [m]
  (select-keys m [:use :load-step-kw :final-freq-hz :freq-restored
                  :final-generation-kw :final-soc :settling-seconds
                  :rocof-max-hz-per-s :rocof-tripped :representative]))

(defn commission-microgrid
  "Run the microgrid acceptance test. RAISES (assert-civilian) before any run.
  Apply load-step-kw, run primary droop + secondary PI, confirm frequency returns to
  50 Hz with generation tracking load and SoC in band."
  [load-step-kw
   & {:keys [use inertia-h initial-soc droop-r p-base-kw kp ki p-max-kw steps dt]
      :or {use "grid-control" inertia-h 4.0 initial-soc 0.6 droop-r 0.04
           p-base-kw 100.0 kp 4.0 ki 20.0 p-max-kw 200.0 steps 8000 dt 0.01}}]
  (sub/assert-civilian use PERMITTED-USES) ; N1 gate before any actuation modelling
  (let [grid (->microgrid-plant {:inertia-h inertia-h :soc initial-soc :p-load 100.0 :f 50.0})
        f-nom (:f-nom @grid)]
    (plant-set-load! grid load-step-kw)
    (let [droop (sub/->droop {:nominal f-nom :droop-r droop-r :p-base p-base-kw
                              :p-min 0.0 :p-max p-max-kw})
          pid (sub/->pid {:kp kp :ki ki :out-min (- p-max-kw) :out-max p-max-kw})
          controller (sub/->droop-pi droop pid)
          res (sub/simulate {:plant grid
                             :measure-fn plant-measure
                             :step-fn plant-step!
                             :controller controller
                             :setpoint f-nom
                             :steps steps :dt dt :tol 1e-2})
          r (rocof (:trajectory res))
          settling-seconds (if (>= (:settling-step res) 0)
                             (* (:settling-step res) dt)
                             -1.0)]
      (->commissioning-result
       {:use use
        :load-step-kw load-step-kw
        :final-freq-hz (sub/py-round (:final-value res) 4)
        :freq-restored (:converged res)
        :final-generation-kw (sub/py-round (nth (peek (:trajectory res)) 2) 4)
        :final-soc (sub/py-round (:soc @grid) 4)
        :settling-seconds (sub/py-round settling-seconds 3)
        :rocof-max-hz-per-s (sub/py-round r 4)
        :rocof-tripped (> r ROCOF-TRIP-HZ-PER-S)
        :representative true}))))

(defn to-datoms
  "Project a commissioning result into kotoba EAVT-shaped datoms (G6). Aggregate-only
  (no smart-meter PII, G9). Python ':…' attr names stay literal string keys."
  [result microgrid-id]
  {":microgrid/id" microgrid-id
   ":microgrid/use" (:use result)
   ":microgrid/load-step-kw" (:load-step-kw result)
   ":microgrid/final-freq-hz" (:final-freq-hz result)
   ":microgrid/freq-restored" (:freq-restored result)
   ":microgrid/final-generation-kw" (:final-generation-kw result)
   ":microgrid/final-soc" (:final-soc result)
   ":microgrid/settling-seconds" (:settling-seconds result)
   ":microgrid/rocof-max-hz-per-s" (:rocof-max-hz-per-s result)
   ":microgrid/rocof-tripped" (:rocof-tripped result)
   ":microgrid/representative" (:representative result) ; G10
   ":microgrid/dry-run" true})                          ; G10: R0 offline only

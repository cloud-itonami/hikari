(ns hikari.methods.substrate
  "substrate — the shared infra-robotics control/kinematics/safety primitives for
  hikari/methods. 1:1 Clojure port of the kuni-umi/robotics reference engine
  (control.py + kinematics.py + plant.py + safety.py), re-exported the way
  `_substrate.py` re-exports them for the hikari method modules.

  NOTE on the ns name: the Python file is `_substrate.py`, but a leading-underscore
  Clojure namespace munges badly under SCI/the JVM classloader (`_substrate` →
  `_substrate.cljc` is fine on disk but the symbol `_substrate` is an invalid first
  segment in practice and confuses tooling). We pick the clean ns
  `hikari.methods.substrate`, resolving to `20-actors/hikari/methods/substrate.cljc`,
  and the siblings `(:require [hikari.methods.substrate :as sub])`.

  PRIMITIVES (open-ot field-tier `:representative` twins — float, offline sim only):
    PID      — limited PID with conditional-integration anti-windup (PID_LIMITED)
    Droop    — proportional frequency/voltage droop (DROOP_P_F)
    DroopPI  — primary droop + secondary PI composite (grid-forming)
    simulate — deterministic closed-loop runner → control-result
    PlanarArm / fk / ik2 / reachable / joint-trajectory — install-robot motion
    SafetyEnvelope + assert-civilian / require-member-signature / witness-quorum-ok

  CRITICAL — civilian / no-server-key / no-live-actuation. These are STRUCTURAL gates:
  a forbidden intent RAISES (ex-info) before any motion/dispatch is modelled.

  House style: Python ':…' keyword strings stay literal strings; Python dict keys ↔
  kebab keyword keys; pure where possible, mutable plant/controller state mirrors the
  Python objects' in-place mutation so the iteration order + accumulation is identical.
  ALL round()/{:.Nf} reproduce Python HALF_EVEN exactly via the exact BigDecimal of the
  double. Math/sin/cos/sqrt/atan2/hypot/pow map directly (last-ULP identical on JVM)."
  #?(:cljs (:require-macros))
  (:require [clojure.string :as str]))

;; ── Python-exact numeric helpers ────────────────────────────────────────────
;; round(x, n) → nearest, HALF_EVEN on the EXACT decimal value of the double, as a
;; double (Python returns a float). java.math.BigDecimal.(double) is the EXACT value;
;; String.format would be HALF_UP, so we never use it.
#?(:clj
   (defn py-round
     "Python round(x, n) — HALF_EVEN on the exact double, returns a double."
     [x n]
     (-> (java.math.BigDecimal. (double x))
         (.setScale (int n) java.math.RoundingMode/HALF_EVEN)
         .doubleValue))
   :cljs
   (defn py-round [x n]
     (js/Number (.toFixed (double x) n))))

#?(:clj
   (defn fmt-fixed
     "Python f\"{x:.Nf}\" — fixed-point, HALF_EVEN on the exact double."
     [x n]
     (-> (java.math.BigDecimal. (double x))
         (.setScale (int n) java.math.RoundingMode/HALF_EVEN)
         .toPlainString))
   :cljs
   (defn fmt-fixed [x n] (.toFixed (double x) n)))

(def inf #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity))
(def neg-inf #?(:clj Double/NEGATIVE_INFINITY :cljs js/-Infinity))

(defn- clamp
  "min(hi, max(lo, x)) — same composition order as Python."
  [x lo hi]
  (min hi (max lo x)))

;; ── safety (structural gates) ───────────────────────────────────────────────
(def MIN-WITNESS-SIGS 2)

;; N1 (Mission Charter §1.12): cross-domain forbidden-force anchors.
(def FORBIDDEN-USES
  ["weapon" "directed-energy" "munition" "fire-control"
   "interdiction" "covert-force" "surveillance-targeting"])

(def ^:private forbidden-set (set FORBIDDEN-USES))

;; SafetyError ≅ Python's SafetyError. We carry it as an ex-info with :error "SafetyError"
;; so callers can `(thrown-with-msg? ... )` and pattern-match the gate.
(defn safety-error
  "Construct a SafetyError-shaped ex-info."
  ([msg] (safety-error msg {}))
  ([msg data] (ex-info msg (assoc data :error "SafetyError"))))

(defn safety-error?
  "True iff the throwable is a SafetyError (matches Python `except SafetyError`)."
  [e]
  (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e)
       (= "SafetyError" (:error (ex-data e)))))

(defn assert-civilian
  "Closed-world civilian-use gate (N1). Raise unless `use` is explicitly permitted.
  Anything in FORBIDDEN-USES is rejected even if the caller lists it."
  [use permitted]
  (when (contains? forbidden-set use)
    (throw (safety-error
            (str "N1: use " (pr-str use) " is a forbidden-force use and can never be energised "
                 "(Mission Charter §1.12 constitutional invariant)")
            {:gate "N1" :use use})))
  (when-not (contains? (set permitted) use)
    (throw (safety-error
            (str "N1: use " (pr-str use) " is not in the civilian allowlist " (pr-str (vec permitted)) "; "
                 "closed-world refusal (only explicitly-permitted civilian uses run)")
            {:gate "N1" :use use}))))

(defn require-member-signature
  "No-server-key gate (G15/G7). Raise unless a member/operator signs and the platform
  holds no key. A non-empty server-sig is a structural violation; an empty member-sig
  means nobody authorised the action."
  ([member-sig] (require-member-signature member-sig ""))
  ([member-sig server-sig]
   (when (and (some? server-sig) (not= server-sig ""))
     (throw (safety-error
             (str "G15/G7 violation: a server/platform signature was supplied; the platform "
                  "holds no key and never signs actuation (ADR-2605231525)")
             {:gate "G15/G7"})))
   (when (or (nil? member-sig) (= member-sig ""))
     (throw (safety-error
             (str "G15/G7 violation: a member/operator signature is required to authorise "
                  "any actuation (no-server-key)")
             {:gate "G15/G7"})))))

(defn witness-quorum-ok
  "Witness quorum >=2 independent robot DIDs (G8). N<2 or duplicates rejected.
  Returns a map (does NOT raise) — kebab keys mirror Python's dict shape."
  [witness-sigs]
  (cond
    (< (count witness-sigs) MIN-WITNESS-SIGS)
    {:ok false
     :reason (str "witness quorum " (count witness-sigs) " < " MIN-WITNESS-SIGS " (G8 constitutional)")
     :escalate-council-lv6 true}

    (< (count (set witness-sigs)) MIN-WITNESS-SIGS)
    {:ok false
     :reason "duplicate witness DIDs detected (G8)"
     :escalate-council-lv6 true}

    :else
    {:ok true :reason "witness quorum satisfied"}))

;; SafetyEnvelope — a frozen dataclass; we model it as a plain map.
(defn ->safety-envelope
  ([] (->safety-envelope {}))
  ([{:keys [max-joint-speed human-proximity-speed max-reach]
     :or {max-joint-speed 1.0 human-proximity-speed 0.25 max-reach inf}}]
   {:max-joint-speed max-joint-speed
    :human-proximity-speed human-proximity-speed
    :max-reach max-reach}))

(defn check-trajectory
  "Validate a joint-space trajectory. Returns {:ok bool :violations [..]}.
  Per-step joint rate |Δq|/dt must stay under the applicable ceiling. The violation
  strings are byte-identical to Python's f-string (rate/ceiling formatted :.4f)."
  ([env trajectory dt] (check-trajectory env trajectory dt false))
  ([env trajectory dt human-present]
   (let [ceiling (if human-present (:human-proximity-speed env) (:max-joint-speed env))
         traj (vec trajectory)
         n (count traj)]
     (loop [i 1, violations (transient [])]
       (if (>= i n)
         (let [v (persistent! violations)]
           {:ok (empty? v) :violations v})
         (let [prev (nth traj (dec i))
               cur (nth traj i)]
           (if (not= (count prev) (count cur))
             (recur (inc i) (conj! violations (str "step " i ": joint-count mismatch")))
             (recur (inc i)
                    (loop [j 0, vs violations]
                      (if (>= j (count prev))
                        vs
                        (let [a (nth prev j) b (nth cur j)
                              rate (if (> dt 0) (/ (Math/abs (- b a)) dt) inf)]
                          (if (> rate (+ ceiling 1e-9))
                            (recur (inc j)
                                   (conj! vs
                                          (str "step " i " joint " j ": rate " (fmt-fixed rate 4)
                                               " > ceiling " (fmt-fixed ceiling 4)
                                               (if human-present " (human present)" ""))))
                            (recur (inc j) vs)))))))))))))

;; ── control: PID ────────────────────────────────────────────────────────────
;; Python PID is a mutable dataclass: ._integral, ._prev_error (nil-able), .saturated.
;; We mirror its in-place mutation with an atom holding those three fields so the
;; simulate loop's accumulation + clamp order is byte-identical.
(defn ->pid
  "Construct a limited PID with anti-windup. out-min/out-max default to ±inf."
  [{:keys [kp ki kd out-min out-max]
    :or {ki 0.0 kd 0.0 out-min neg-inf out-max inf}}]
  {:kp kp :ki ki :kd kd :out-min out-min :out-max out-max
   :state (atom {:integral 0.0 :prev-error nil :saturated false})})

(defn pid-reset [pid]
  (reset! (:state pid) {:integral 0.0 :prev-error nil :saturated false})
  nil)

(defn pid-step
  "One PID step. error = setpoint − measured. Conditional integration anti-windup:
  the tentative integral is committed ONLY if the unclamped command does not saturate.
  Returns the clamped command; mutates the controller state in place."
  [pid error dt]
  (let [{:keys [kp ki kd out-min out-max state]} pid
        {:keys [integral prev-error]} @state
        deriv (if (and (some? prev-error) (> dt 0))
                (/ (- error prev-error) dt)
                0.0)
        tentative-integral (+ integral (* error dt))
        raw (+ (* kp error) (* ki tentative-integral) (* kd deriv))
        clamped (clamp raw out-min out-max)
        saturated (not= clamped raw)]
    (swap! state
           (fn [s]
             (-> s
                 (assoc :saturated saturated)
                 (assoc :integral (if saturated integral tentative-integral))
                 (assoc :prev-error error))))
    clamped))

;; ── control: Droop ──────────────────────────────────────────────────────────
(defn ->droop
  "Proportional frequency/voltage droop. p-min/p-max default to ±inf."
  [{:keys [nominal droop-r p-base p-min p-max]
    :or {p-base 0.0 p-min neg-inf p-max inf}}]
  {:nominal nominal :droop-r droop-r :p-base p-base :p-min p-min :p-max p-max})

(defn droop-command
  "P = P_base + (nominal − measured)/R, clamped to [p_min, p_max]."
  [droop measured]
  (let [{:keys [nominal droop-r p-base p-min p-max]} droop
        p (+ p-base (/ (- nominal measured) droop-r))]
    (clamp p p-min p-max)))

;; ── control: DroopPI (primary droop + secondary PI) ──────────────────────────
(defn ->droop-pi [droop pid]
  {:droop droop :pid pid})

(defn droop-pi-reset [dpi]
  (pid-reset (:pid dpi))
  nil)

(defn droop-pi-step
  "measured = nominal − error; cmd = droop.command(measured) + pid.step(error,dt),
  clamped to the droop power band. SAME order as Python."
  [dpi error dt]
  (let [{:keys [droop pid]} dpi
        measured (- (:nominal droop) error)
        cmd (+ (droop-command droop measured) (pid-step pid error dt))]
    (clamp cmd (:p-min droop) (:p-max droop))))

;; A controller is anything with a -reset and -step. simulate dispatches on shape:
;; DroopPI maps carry :droop+:pid, a bare PID carries :kp. (Mirrors Python duck typing.)
(defn- controller-reset [c]
  (if (:droop c) (droop-pi-reset c) (pid-reset c)))
(defn- controller-step [c error dt]
  (if (:droop c) (droop-pi-step c error dt) (pid-step c error dt)))

;; ── control: simulate (deterministic closed-loop runner) ─────────────────────
;; A plant is a map of mutable state inside an atom plus a step-fn / measure-fn.
;; The microgrid plant is supplied by hikari.methods.microgrid; here we only need a
;; protocol-ish contract: (measure plant) -> double, (step! plant cmd dt) -> nil.
(defn control-result
  "Frozen ControlResult ≅ Python dataclass. Kebab keys. final-value/steady-error/
  max-abs-error rounded to 6 dp (Python round(...,6)); trajectory t rounded to 6 dp."
  [{:keys [setpoint final-value steady-error converged settling-step
           max-abs-error steps trajectory]}]
  {:setpoint setpoint
   :final-value final-value
   :steady-error steady-error
   :converged converged
   :settling-step settling-step
   :max-abs-error max-abs-error
   :steps steps
   :trajectory trajectory})

(defn simulate
  "Run a closed loop against a plant and report convergence. measure-fn/step-fn are the
  plant accessors (measure plant) and (step! plant cmd dt). Deterministic; same step
  sequence + accumulation as control.py.

  `converged` ⇔ |error| < tol for the last `settle-window` steps. `settling-step` is the
  first index from which the error never again exceeds tol; -1 if never."
  [{:keys [plant measure-fn step-fn controller setpoint steps dt tol settle-window]
    :or {tol 1e-3 settle-window 10}}]
  (controller-reset controller)
  (let [traj (transient [])
        errors (transient [])]
    (loop [k 0, max-abs 0.0]
      (if (>= k steps)
        (let [errors* (persistent! errors)
              traj* (persistent! traj)
              final-pv (measure-fn plant)
              steady-error (- setpoint final-pv)
              n (count errors*)
              settling-step
              (loop [i 0]
                (cond
                  (>= i n) -1
                  (every? #(< % tol) (subvec errors* i)) i
                  :else (recur (inc i))))
              tail (if (>= n settle-window) (subvec errors* (- n settle-window)) errors*)
              converged (boolean (and (seq tail) (every? #(< % tol) tail)))]
          (control-result
           {:setpoint setpoint
            :final-value (py-round final-pv 6)
            :steady-error (py-round steady-error 6)
            :converged converged
            :settling-step settling-step
            :max-abs-error (py-round max-abs 6)
            :steps steps
            :trajectory traj*}))
        (let [pv (measure-fn plant)
              error (- setpoint pv)
              cmd (controller-step controller error dt)]
          (conj! traj [(py-round (* k dt) 6) pv cmd])
          (conj! errors (Math/abs error))
          (let [max-abs' (max max-abs (Math/abs error))]
            (step-fn plant cmd dt)
            (recur (inc k) max-abs')))))))

;; ── kinematics: PlanarArm + trajectory ───────────────────────────────────────
(defn ->planar-arm
  "A planar serial arm defined by its link lengths (metres)."
  [link-lengths]
  {:link-lengths (vec link-lengths)})

(defn max-reach [arm] (reduce + 0.0 (:link-lengths arm)))

(defn min-reach
  "Inner workspace radius (0 if any single link can fold inside the rest)."
  [arm]
  (let [longest (apply max (:link-lengths arm))
        rest (- (max-reach arm) longest)]
    (max 0.0 (- longest rest))))

(defn fk
  "Forward kinematics: relative joint angles (rad) → end-effector pose {:x :y :theta}.
  Each coordinate rounded to 9 dp (Python round(...,9))."
  [arm joints]
  (when (not= (count joints) (count (:link-lengths arm)))
    (throw (ex-info (str "expected " (count (:link-lengths arm)) " joints, got " (count joints)) {})))
  (loop [pairs (map vector (:link-lengths arm) joints)
         x 0.0 y 0.0 theta 0.0]
    (if (empty? pairs)
      {:x (py-round x 9) :y (py-round y 9) :theta (py-round theta 9)}
      (let [[length q] (first pairs)
            theta' (+ theta q)]
        (recur (rest pairs)
               (+ x (* length (Math/cos theta')))
               (+ y (* length (Math/sin theta')))
               theta')))))

(defn reachable [arm x y]
  (let [r (Math/hypot x y)]
    (and (<= (- (min-reach arm) 1e-9) r)
         (<= r (+ (max-reach arm) 1e-9)))))

(defn ik2
  "Analytic 2-link inverse kinematics. (q0,q1) in radians (rounded 9 dp), or nil if
  unreachable. elbow-up selects between the two mirror solutions. SAME atan2 arg order."
  ([arm x y] (ik2 arm x y true))
  ([arm x y elbow-up]
   (when (not= 2 (count (:link-lengths arm)))
     (throw (ex-info "ik2 requires a 2-link arm" {})))
   (let [[l1 l2] (:link-lengths arm)
         r2 (+ (* x x) (* y y))
         cos-q1 (/ (- r2 (* l1 l1) (* l2 l2)) (* 2.0 l1 l2))]
     (if (or (< cos-q1 (- -1.0 1e-9)) (> cos-q1 (+ 1.0 1e-9)))
       nil
       (let [cos-q1 (clamp cos-q1 -1.0 1.0)
             sin-q1 (Math/sqrt (max 0.0 (- 1.0 (* cos-q1 cos-q1))))
             sin-q1 (if elbow-up (- sin-q1) sin-q1)
             q1 (Math/atan2 sin-q1 cos-q1)
             q0 (- (Math/atan2 y x)
                   (Math/atan2 (* l2 (Math/sin q1)) (+ l1 (* l2 (Math/cos q1)))))]
         [(py-round q0 9) (py-round q1 9)])))))

(defn joint-trajectory
  "Linear joint-space interpolation from q-start to q-goal over `steps` steps.
  Returns steps+1 configurations (both endpoints inclusive). a = k/steps with integer
  k over a float steps, matching Python's k/steps."
  [q-start q-goal steps]
  (when (not= (count q-start) (count q-goal))
    (throw (ex-info "start and goal must have equal joint count" {})))
  (when (< steps 1)
    (throw (ex-info "steps must be >= 1" {})))
  (let [qs (vec q-start) qg (vec q-goal)]
    (vec (for [k (range (inc steps))]
           (let [a (/ (double k) steps)]
             (vec (map (fn [s g] (+ s (* a (- g s)))) qs qg)))))))

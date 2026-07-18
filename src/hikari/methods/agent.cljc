(ns hikari.methods.agent
  "hikari 光 — energy generation/storage/grid-edge langgraph actor (kotoba WASM cell).

  ADR-2605261100, migration plan Phase 3. Runs in-WASM on kotoba :8077. Five handlers
  over one kotoba EAVT graph, mirroring the energy lifecycle:

    handle-solar-pv-install    parcel assessment → sourcing audit → biodiversity → attestation
    handle-storage-battery     chemistry validation → SoC/SoH → generation record
    handle-geothermal-micro    thermal gradient → loop design → baseload record
    handle-grid-edge           real-time aggregation → load balancing → grid state
    handle-consumption-audit   per-load aggregation → encryption envelope → audit record

  LLM access is Murakumo-only via KotobaLLM (127.0.0.1:4000, gemma3:4b; G5). State is
  written back to the kotoba Datom log (G6). Settlement is USDC on Base L2 + ERC-4337
  + TitheRouter 10% only — no fiat (G7). The platform holds no key; operator signs
  (G8). R0 is compute-only; real dispatch gated by Council ratification (G10).

  1:1 faithful port of py/agent.py (ADR-2605261100 Phase 3).
  State maps use keyword keys (Python dict str keys → Clojure keywords), preserving
  the exact numeric semantics verified by test_agent.py."
  (:require [clojure.string :as str]))

;; ── constants ────────────────────────────────────────────────────────────────
(def TITHE-BPS 1000)   ; 10% TitheRouter auto-split, basis points

;; Renewable-only source allowlist (G8 gate: fossil/non-renewable gen unrepresentable)
(def RENEWABLE-SOURCES #{"solar-pv" "battery-lifepo4" "geothermal-loop"})

;; Valid battery chemistries (G3 chemistry attestation)
(def VALID-CHEMISTRIES #{"lifepo4" "nca" "nmc"})

;; ── handle-solar-pv-install ──────────────────────────────────────────────────
(defn handle-solar-pv-install
  "Parcel solar assessment → sourcing audit → attestation.
  Requires :parcel_did and :location; estimates kWh from :area_sqm.

  Python: estimated_kwh = max(1, area_sqm // 10)
  attestation_id = 'pea.' + parcel_did.split('/')[-1]"
  [state]
  (let [parcel-did (get state :parcel_did "")
        location   (get state :location "")
        area-sqm   (long (get state :area_sqm 0))]
    (if (or (str/blank? parcel-did) (str/blank? location))
      (assoc state :error "parcel_did and location required")
      (let [estimated-kwh (max 1 (quot area-sqm 10))
            last-part     (last (str/split parcel-did #"/"))]
        (merge state
               {:solar_potential_kwh estimated-kwh
                :biodiversity_ok     (get state :biodiversity_ok true)
                :sourcing_audit      "pending"
                :attestation_id      (str "pea." last-part)})))))

;; ── handle-storage-battery ──────────────────────────────────────────────────
(defn handle-storage-battery
  "Battery chemistry + SoC/SoH monitoring → generation record.
  Validates chemistry ∈ {lifepo4, nca, nmc}; enforces MDI ≤ 5 ppb, TDI ≤ 2 ppb.

  Python gate order: battery_id BLANK or chemistry NOT IN allowlist → error first,
  then exposure check."
  [state]
  (let [battery-id (get state :battery_id "")
        chemistry  (get state :chemistry "")
        mdi-ppb    (double (get state :mdi_ppb 0.0))
        tdi-ppb    (double (get state :tdi_ppb 0.0))
        soc-pct    (get state :soc_pct 75)]
    (cond
      (or (str/blank? battery-id)
          (not (VALID-CHEMISTRIES chemistry)))
      (assoc state :error (str "unknown chemistry " chemistry))

      (or (> mdi-ppb 5.0) (> tdi-ppb 2.0))
      (assoc state :error "worker exposure limits exceeded")

      :else
      (merge state
             {:chemistry_ok         true
              :soc_pct              soc-pct
              :generation_record_id (str "gen." battery-id)}))))

;; ── handle-geothermal-micro ──────────────────────────────────────────────────
(defn handle-geothermal-micro
  "Thermal gradient assessment → geothermal potential.
  Depth > 500 m → infeasible check fires FIRST (before parcel_did check).

  Python geo_kw: 3 if depth_m >= 200 else 1 if depth_m >= 100 else 0"
  [state]
  (let [parcel-did (get state :parcel_did "")
        depth-m    (long (get state :depth_m 0))]
    (cond
      (> depth-m 500)
      (assoc state :error "depth > 500 m (infeasible)")

      (str/blank? parcel-did)
      (assoc state :error "parcel_did required")

      :else
      (let [geo-kw    (cond
                        (>= depth-m 200) 3
                        (>= depth-m 100) 1
                        :else            0)
            last-part (last (str/split parcel-did #"/"))]
        (merge state
               {:geo_potential_kw     geo-kw
                :feasible             (> geo-kw 0)
                :generation_record_id (str "gen.geo." last-part)})))))

;; ── handle-grid-edge ─────────────────────────────────────────────────────────
(defn handle-grid-edge
  "Real-time load balancing — net load, frequency, battery SoC.
  net_kw = (generation_kwh - consumption_kwh) // 6  (Python integer division)
  frequency_hz = 50 if battery_soc_pct > 30 else 49
  grid_ok = battery_soc_pct >= 20
  grid_record_id = 'grid.' + str(int(state.get('timestamp', '0')))"
  [state]
  (let [gen-kwh    (long (get state :generation_kwh 0))
        cons-kwh   (long (get state :consumption_kwh 0))
        battery-soc (long (get state :battery_soc_pct 75))
        net-kw      (quot (- gen-kwh cons-kwh) 6)
        freq-hz     (if (> battery-soc 30) 50 49)
        grid-ok     (>= battery-soc 20)
        ts          (str (long (let [v (get state :timestamp "0")]
                                 (if (string? v)
                                   (try (Long/parseLong v) (catch Exception _ 0))
                                   (long v)))))]
    (merge state
           {:net_load_kw     net-kw
            :frequency_hz    freq-hz
            :battery_soc_pct battery-soc
            :grid_ok         grid-ok
            :grid_record_id  (str "grid." ts)})))

;; ── handle-consumption-audit ─────────────────────────────────────────────────
(defn handle-consumption-audit
  "Per-facility consumption → aggregate + encrypted detail.
  Requires :period_start and :period_end.

  record_id = 'cons.' + (facility_did.split('/')[-1] if facility_did else 'adherent')
  detail_encrypted_cid = 'ipfs://bafy...encrypted'"
  [state]
  (let [period-start (get state :period_start "")
        period-end   (get state :period_end "")
        facility-did (get state :facility_did "")
        kwh          (get state :kwh 0)]
    (if (or (str/blank? period-start) (str/blank? period-end))
      (assoc state :error "period_start and period_end required")
      (let [record-suffix (if (str/blank? facility-did)
                            "adherent"
                            (last (str/split facility-did #"/")))]
        (merge state
               {:record_id            (str "cons." record-suffix)
                :period               (str period-start "/" period-end)
                :kwh_aggregate        kwh
                :detail_encrypted_cid "ipfs://bafy...encrypted"})))))

;; ── build-settlement-intent ───────────────────────────────────────────────────
(defn build-settlement-intent
  "Compute the USDC settlement split. 10% tithe → Public Fund.
  tithe = (gross_minor * TITHE_BPS) // 10_000  (Python integer division)
  state = 'executed' if buyer_sig_ref else 'intent' (G7/G8)."
  ([gross-minor]
   (build-settlement-intent gross-minor nil))
  ([gross-minor buyer-sig-ref]
   (let [gross (long gross-minor)
         tithe (quot (* gross TITHE-BPS) 10000)]
     {:rail                "usdc-base-l2"
      :grossMinor          gross
      :titheMinor          tithe
      :operatorPayoutMinor (- gross tithe)
      :titheRouter         "50-infra/etzhayyim-tithe-router"
      :state               (if buyer-sig-ref "executed" "intent")
      :operatorSigRef      (or buyer-sig-ref "")})))

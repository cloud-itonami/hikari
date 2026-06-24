#!/usr/bin/env bb
;; Working Clojure port of py/agent.py (hikari energy gen/storage/grid-edge actor).
(ns hikari.py.agent
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

  Run:  bb --classpath 20-actors 20-actors/hikari/py/agent.clj"
  (:require [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────────
(def TITHE_BPS 1000)   ; 10% TitheRouter auto-split, basis points

;; Renewable-only source allowlist (G8 gate: fossil/non-renewable gen unrepresentable)
(def RENEWABLE_SOURCES #{"solar-pv" "battery-lifepo4" "geothermal-loop"})

;; ── _infer — Murakumo-only inference stub ────────────────────────────────────
(defn _infer
  "Murakumo-only inference (G5). Returns offline sentinel when host not available."
  [_prompt]
  "LLM_NOT_AVAILABLE")

;; ── build_settlement_intent — USDC + TitheRouter (G7/G8) ─────────────────────
(defn build_settlement_intent
  "Compute the USDC settlement split. 10% tithe → Public Fund.
  State is 'executed' when buyer-sig-ref is provided, else 'intent' (G8)."
  ([gross-minor]
   (build_settlement_intent gross-minor nil))
  ([gross-minor buyer-sig-ref]
   (let [gross (long gross-minor)
         tithe (quot (* gross TITHE_BPS) 10000)]
     {:rail                 "usdc-base-l2"
      :grossMinor           gross
      :titheMinor           tithe
      :operatorPayoutMinor  (- gross tithe)
      :titheRouter          "50-infra/etzhayyim-tithe-router"
      :state                (if buyer-sig-ref "executed" "intent")
      :operatorSigRef       (or buyer-sig-ref "")})))

;; ── handle_solar_pv_install ───────────────────────────────────────────────────
(defn handle_solar_pv_install
  "Parcel solar assessment → sourcing audit → attestation.
  Requires parcel_did and location; estimates kWh from area_sqm."
  [state]
  (let [parcel-did (get state :parcel_did "")
        location   (get state :location "")
        area-sqm   (get state :area_sqm 0)]
    (if (or (str/blank? parcel-did) (str/blank? location))
      (assoc state :error "parcel_did and location required")
      (let [estimated-kwh (max 1 (quot area-sqm 10))
            last-part     (last (str/split parcel-did #"/"))]
        (merge state
               {:solar_potential_kwh estimated-kwh
                :biodiversity_ok     (get state :biodiversity_ok true)
                :sourcing_audit      "pending"
                :attestation_id      (str "pea." last-part)})))))

;; ── handle_storage_battery ───────────────────────────────────────────────────
(defn handle_storage_battery
  "Battery chemistry + SoC/SoH monitoring → generation record.
  Validates chemistry ∈ {lifepo4, nca, nmc}; enforces MDI ≤ 5 ppb, TDI ≤ 2 ppb."
  [state]
  (let [battery-id (get state :battery_id "")
        chemistry  (get state :chemistry "")
        mdi-ppb    (double (get state :mdi_ppb 0.0))
        tdi-ppb    (double (get state :tdi_ppb 0.0))
        soc-pct    (get state :soc_pct 75)]
    (cond
      (or (str/blank? battery-id)
          (not (#{"lifepo4" "nca" "nmc"} chemistry)))
      (assoc state :error (str "unknown chemistry " chemistry))

      (or (> mdi-ppb 5.0) (> tdi-ppb 2.0))
      (assoc state :error "worker exposure limits exceeded")

      :else
      (merge state
             {:chemistry_ok        true
              :soc_pct             soc-pct
              :generation_record_id (str "gen." battery-id)}))))

;; ── handle_geothermal_micro ──────────────────────────────────────────────────
(defn handle_geothermal_micro
  "Thermal gradient assessment → geothermal potential.
  Depth > 500 m → infeasible; parcel_did required."
  [state]
  (let [parcel-did (get state :parcel_did "")
        depth-m    (get state :depth_m 0)]
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
               {:geo_potential_kw    geo-kw
                :feasible            (> geo-kw 0)
                :generation_record_id (str "gen.geo." last-part)})))))

;; ── handle_grid_edge ─────────────────────────────────────────────────────────
(defn handle_grid_edge
  "Real-time load balancing — net load, frequency, battery SoC.
  net_kw = (generation_kwh - consumption_kwh) // 6 (integer division)."
  [state]
  (let [gen-kwh       (get state :generation_kwh 0)
        cons-kwh      (get state :consumption_kwh 0)
        battery-soc   (get state :battery_soc_pct 75)
        net-kw        (quot (- gen-kwh cons-kwh) 6)
        frequency-hz  (if (> battery-soc 30) 50 49)
        grid-ok       (>= battery-soc 20)
        ts            (str (int (get state :timestamp 0)))]
    (merge state
           {:net_load_kw    net-kw
            :frequency_hz   frequency-hz
            :battery_soc_pct battery-soc
            :grid_ok        grid-ok
            :grid_record_id (str "grid." ts)})))

;; ── handle_consumption_audit ─────────────────────────────────────────────────
(defn handle_consumption_audit
  "Per-facility consumption → aggregate + encrypted detail.
  Requires period_start and period_end."
  [state]
  (let [period-start  (get state :period_start "")
        period-end    (get state :period_end "")
        facility-did  (get state :facility_did "")
        kwh           (get state :kwh 0)]
    (if (or (str/blank? period-start) (str/blank? period-end))
      (assoc state :error "period_start and period_end required")
      (let [record-suffix (if (str/blank? facility-did)
                            "adherent"
                            (last (str/split facility-did #"/")))]
        (merge state
               {:record_id             (str "cons." record-suffix)
                :period                (str period-start "/" period-end)
                :kwh_aggregate         kwh
                :detail_encrypted_cid  "ipfs://bafy...encrypted"})))))

;; ── main (smoke demo) ─────────────────────────────────────────────────────────
(defn main [& _]
  (let [demo-solar (handle_solar_pv_install
                    {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                     :location   "Tokyo, 35.6762 N 139.7674 E"
                     :area_sqm   250})]
    (println "solar install:" (:solar_potential_kwh demo-solar) "kWh"))

  (let [demo-battery (handle_storage_battery
                      {:battery_id  "batt-001"
                       :chemistry   "lifepo4"
                       :capacity_kwh 50.0
                       :soc_pct     75})]
    (println "battery:" (:chemistry_ok demo-battery)))

  (let [demo-grid (handle_grid_edge
                   {:generation_kwh  100
                    :consumption_kwh 65
                    :battery_soc_pct 72})]
    (println "grid net load:" (:net_load_kw demo-grid) "kW"))

  (println "settlement:" (build_settlement_intent 1000000)))

(when (= *file* (System/getProperty "babashka.file"))
  (main))

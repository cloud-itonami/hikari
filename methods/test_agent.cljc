(ns hikari.methods.test-agent
  "hikari 光 — agent cell tests (no kotoba host, no network, no LLM).

  ADR-2605261100 Phase 3. 1:1 port of py/test_agent.py.
  Exercises the 5 handlers + settlement + gates offline (Murakumo-only invariant
  untouched; G5). Expected values are copied VERBATIM from test_agent.py."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [hikari.methods.agent :as agent]))

;; ── handle-solar-pv-install ──────────────────────────────────────────────────

(deftest test-solar-pv-install-estimates-kwh
  ;; test_solar_pv_install_estimates_kwh: out.get("solar_potential_kwh") == 25
  (testing "solar PV estimates kWh from area"
    (let [out (agent/handle-solar-pv-install
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :location   "Tokyo"
                :area_sqm   250})]
      (is (= 25 (:solar_potential_kwh out))))))

(deftest test-solar-pv-requires-parcel-did
  ;; test_solar_pv_requires_parcel_did: out.get("error") is not None
  (testing "solar PV requires parcel_did"
    (let [out (agent/handle-solar-pv-install {:location "Tokyo" :area_sqm 250})]
      (is (some? (:error out))))))

;; ── handle-storage-battery ───────────────────────────────────────────────────

(deftest test-battery-validates-chemistry
  ;; test_battery_validates_chemistry: out.get("error") is not None
  (testing "battery rejects unknown chemistry"
    (let [out (agent/handle-storage-battery
               {:battery_id   "b1"
                :chemistry    "invalid"
                :capacity_kwh 50.0})]
      (is (some? (:error out))))))

(deftest test-battery-accepts-lifepo4
  ;; test_battery_accepts_lifepo4: out.get("chemistry_ok") is True
  (testing "battery accepts lifepo4 chemistry"
    (let [out (agent/handle-storage-battery
               {:battery_id   "b1"
                :chemistry    "lifepo4"
                :capacity_kwh 50.0
                :soc_pct      75})]
      (is (true? (:chemistry_ok out))))))

;; ── handle-geothermal-micro ──────────────────────────────────────────────────

(deftest test-geothermal-depth-limit
  ;; test_geothermal_depth_limit: out.get("error") is not None  (depth=600)
  (testing "geothermal rejects depth > 500 m"
    (let [out (agent/handle-geothermal-micro
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :depth_m    600})]
      (is (some? (:error out))))))

(deftest test-geothermal-potential-at-200m
  ;; test_geothermal_potential_at_200m: out.get("geo_potential_kw") == 3
  (testing "geothermal potential 3 kW at 200 m"
    (let [out (agent/handle-geothermal-micro
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :depth_m    200})]
      (is (= 3 (:geo_potential_kw out))))))

;; ── handle-grid-edge ─────────────────────────────────────────────────────────

(deftest test-grid-edge-net-load
  ;; test_grid_edge_net_load: out.get("net_load_kw") == 5  ( (100-65)//6 = 5 )
  (testing "grid net load = (100 - 65) / 6 = 5 kW"
    (let [out (agent/handle-grid-edge
               {:generation_kwh  100
                :consumption_kwh 65
                :battery_soc_pct 72})]
      (is (= 5 (:net_load_kw out))))))

(deftest test-grid-edge-frequency-low-soc
  ;; test_grid_edge_frequency_low_soc: out.get("frequency_hz") == 49  (soc=25, <30)
  (testing "grid frequency 49 Hz when battery SoC < 30 %"
    (let [out (agent/handle-grid-edge
               {:generation_kwh  50
                :consumption_kwh 45
                :battery_soc_pct 25})]
      (is (= 49 (:frequency_hz out))))))

(deftest test-grid-edge-grid-ok-threshold
  ;; test_grid_edge_grid_ok_threshold: out.get("grid_ok") is False  (soc=15, <20)
  (testing "grid not ok when battery SoC < 20 %"
    (let [out (agent/handle-grid-edge
               {:generation_kwh  50
                :consumption_kwh 45
                :battery_soc_pct 15})]
      (is (false? (:grid_ok out))))))

;; ── handle-consumption-audit ─────────────────────────────────────────────────

(deftest test-consumption-audit-requires-period
  ;; test_consumption_audit_requires_period: out.get("error") is not None
  (testing "consumption audit requires period_start/end"
    (let [out (agent/handle-consumption-audit {:facility_did "f1" :kwh 35})]
      (is (some? (:error out))))))

(deftest test-consumption-audit-encrypts-detail
  ;; test_consumption_audit_encrypts_detail:
  ;;   out.get("detail_encrypted_cid") is not None AND "ipfs://" in ...
  (testing "consumption audit detail encrypted (G9)"
    (let [out (agent/handle-consumption-audit
               {:period_start "2026-06-02T00:00:00Z"
                :period_end   "2026-06-02T06:00:00Z"
                :facility_did "did:web:mitsuho.example.com"
                :kwh          35})]
      (is (some? (:detail_encrypted_cid out)))
      (is (str/includes? (str (:detail_encrypted_cid out)) "ipfs://")))))

;; ── build-settlement-intent ──────────────────────────────────────────────────

(deftest test-settlement-tithe-split
  ;; test_settlement_tithe_split:
  ;;   s["titheMinor"] == 100_000_000
  ;;   s["operatorPayoutMinor"] == 900_000_000
  ;;   s["state"] == "intent"
  ;;   s["rail"] == "usdc-base-l2"
  (testing "10% tithe split + stops at intent (G7/G8)"
    (let [s (agent/build-settlement-intent 1000000000)]
      (is (= 100000000 (:titheMinor s)))
      (is (= 900000000 (:operatorPayoutMinor s)))
      (is (= "intent"     (:state s)))
      (is (= "usdc-base-l2" (:rail s))))))

(deftest test-settlement-executed-only-with-sig
  ;; test_settlement_executed_only_with_sig: s["state"] == "executed"
  (testing "settlement executes only with operator signature (G8)"
    (let [s (agent/build-settlement-intent 500000000 "0xopsig")]
      (is (= "executed" (:state s))))))

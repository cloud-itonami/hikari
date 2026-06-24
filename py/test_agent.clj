#!/usr/bin/env bb
;; Clojure port of py/test_agent.py (hikari energy gen/storage/grid-edge agent tests).
(ns hikari.py.test-agent
  "hikari 光 — agent cell tests (no kotoba host, no network, no LLM).

  ADR-2605261100. Exercises the 5 handlers + settlement + gates with injected
  functions so the suite runs offline (Murakumo-only invariant untouched; G5).

      bb --classpath 20-actors 20-actors/hikari/py/test_agent.clj"
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [hikari.py.agent :as agent]))

;; ── handle_solar_pv_install ──────────────────────────────────────────────────

(deftest test-solar-pv-install-estimates-kwh
  (testing "solar PV estimates kWh from area"
    (let [out (agent/handle_solar_pv_install
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :location   "Tokyo"
                :area_sqm   250})]
      (is (= 25 (:solar_potential_kwh out))))))

(deftest test-solar-pv-requires-parcel-did
  (testing "solar PV requires parcel_did"
    (let [out (agent/handle_solar_pv_install {:location "Tokyo" :area_sqm 250})]
      (is (some? (:error out))))))

;; ── handle_storage_battery ───────────────────────────────────────────────────

(deftest test-battery-validates-chemistry
  (testing "battery rejects unknown chemistry"
    (let [out (agent/handle_storage_battery
               {:battery_id  "b1"
                :chemistry   "invalid"
                :capacity_kwh 50.0})]
      (is (some? (:error out))))))

(deftest test-battery-accepts-lifepo4
  (testing "battery accepts lifepo4 chemistry"
    (let [out (agent/handle_storage_battery
               {:battery_id  "b1"
                :chemistry   "lifepo4"
                :capacity_kwh 50.0
                :soc_pct     75})]
      (is (true? (:chemistry_ok out))))))

;; ── handle_geothermal_micro ──────────────────────────────────────────────────

(deftest test-geothermal-depth-limit
  (testing "geothermal rejects depth > 500 m"
    (let [out (agent/handle_geothermal_micro
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :depth_m    600})]
      (is (some? (:error out))))))

(deftest test-geothermal-potential-at-200m
  (testing "geothermal potential 3 kW at 200 m"
    (let [out (agent/handle_geothermal_micro
               {:parcel_did "did:web:etzhayyim.com:lands:parcel/jp-001"
                :depth_m    200})]
      (is (= 3 (:geo_potential_kw out))))))

;; ── handle_grid_edge ─────────────────────────────────────────────────────────

(deftest test-grid-edge-net-load
  (testing "grid net load = (100 - 65) / 6 ≈ 5 kW"
    (let [out (agent/handle_grid_edge
               {:generation_kwh  100
                :consumption_kwh 65
                :battery_soc_pct 72})]
      (is (= 5 (:net_load_kw out))))))

(deftest test-grid-edge-frequency-low-soc
  (testing "grid frequency 49 Hz when battery SoC < 30 %"
    (let [out (agent/handle_grid_edge
               {:generation_kwh  50
                :consumption_kwh 45
                :battery_soc_pct 25})]
      (is (= 49 (:frequency_hz out))))))

(deftest test-grid-edge-grid-ok-threshold
  (testing "grid not ok when battery SoC < 20 %"
    (let [out (agent/handle_grid_edge
               {:generation_kwh  50
                :consumption_kwh 45
                :battery_soc_pct 15})]
      (is (false? (:grid_ok out))))))

;; ── handle_consumption_audit ─────────────────────────────────────────────────

(deftest test-consumption-audit-requires-period
  (testing "consumption audit requires period_start/end"
    (let [out (agent/handle_consumption_audit {:facility_did "f1" :kwh 35})]
      (is (some? (:error out))))))

(deftest test-consumption-audit-encrypts-detail
  (testing "consumption audit detail encrypted (G9)"
    (let [out (agent/handle_consumption_audit
               {:period_start "2026-06-02T00:00:00Z"
                :period_end   "2026-06-02T06:00:00Z"
                :facility_did "did:web:mitsuho.example.com"
                :kwh          35})]
      (is (some? (:detail_encrypted_cid out)))
      (is (str/includes? (:detail_encrypted_cid out) "ipfs://")))))

;; ── build_settlement_intent ──────────────────────────────────────────────────

(deftest test-settlement-tithe-split
  (testing "10% tithe split + stops at intent (G7/G8)"
    (let [s (agent/build_settlement_intent 1000000000)]
      (is (= 100000000 (:titheMinor s)))
      (is (= 900000000 (:operatorPayoutMinor s)))
      (is (= "intent" (:state s)))
      (is (= "usdc-base-l2" (:rail s))))))

(deftest test-settlement-executed-only-with-sig
  (testing "settlement executes only with operator signature (G8)"
    (let [s (agent/build_settlement_intent 500000000 "0xopsig")]
      (is (= "executed" (:state s))))))

;; ── runner ────────────────────────────────────────────────────────────────────

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests 'hikari.py.test-agent)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(ns hikari.methods.test-charter-gates
  "hikari — constitutional-gate conformance tests (manifest + central lexicons).
  Substrate-native Clojure (ADR-2606160842). 1:1 port of the pruned methods/test_charter_gates.py."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.set :as set]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def ^:private here (.getParentFile (java.io.File. ^String *file*)))      ;; methods/
(def ^:private actor-dir (.getParentFile here))                          ;; hikari/
(def ^:private actor-name (.getName actor-dir))
(def ^:private root (.. actor-dir getParentFile getParentFile))          ;; 20-actors → ROOT
(def ^:private lexdir (java.io.File. root (str "00-contracts/lexicons/com/etzhayyim/" actor-name)))
(defn- manifest [] (json/parse-string (slurp (java.io.File. actor-dir "manifest.jsonld"))))
(defn- lex [name] (json/parse-string (slurp (java.io.File. lexdir (str name ".json")))))

(def ^:private components
  #{"solar-pv" "battery-bank" "inverter" "wind-turbine" "geothermal-well" "heat-pump"})
(def ^:private magnets
  #{"open-coil-electrically-excited" "ferrite" "none-not-applicable"})
(def ^:private fossil-nuclear
  ["diesel" "gas" "coal" "fossil" "nuclear" "reactor" "fission" "generator-fossil"])

(defn- collect [doc attr]
  (let [acc (atom {})]
    (letfn [(walk [x parent]
              (cond (map? x) (do (when (and (string? parent) (contains? x attr))
                                   (swap! acc assoc parent (get x attr)))
                                 (doseq [[k v] x] (walk v k)))
                    (sequential? x) (doseq [v x] (walk v parent))))]
      (walk doc nil)) @acc))
(defn- known [doc field] (some-> (get (collect doc "knownValues") field) set))
(defn- required-union [doc]
  (let [acc (atom #{})]
    (letfn [(walk [x] (cond (map? x) (do (when (sequential? (get x "required")) (swap! acc into (get x "required")))
                                         (doseq [v (vals x)] (walk v)))
                            (sequential? x) (doseq [v x] (walk v))))]
      (walk doc)) @acc))

;; ── full gate set ──
(deftest test-all-14-gates-declared
  (is (= (set (keys (get-in (manifest) ["constitutionalGates" "gates"])))
         (set (map #(str "G" %) (range 1 15))))))

;; ── G4/G5 — component vocabulary is renewable-only (no fossil / nuclear) ──
(deftest test-g4-g5-no-fossil-nuclear-components
  (let [comps (known (lex "installAttestation") "componentType")]
    (is (= components comps))
    (doseq [c comps]
      (is (not (some #(str/includes? (str/lower-case c) %) fossil-nuclear))))))

;; ── G8 — no rare-earth permanent magnets ──
(deftest test-g8-no-rare-earth-magnets
  (is (= magnets (known (lex "installAttestation") "magnetAttestation"))))

;; ── G3 — battery chemistry attestation ──
(deftest test-g3-battery-chemistry
  (let [chem (known (lex "installAttestation") "chemistryAttestation")]
    (is (and (seq chem)
             (set/subset? chem #{"LFP" "NMC-restricted" "sodium-ion" "none-not-battery"})))))

;; ── G2 — sourcing audit on every install ──
(deftest test-g2-sourcing-audit
  (let [req (required-union (lex "installAttestation"))]
    (doseq [field ["sourcingAuditCid" "attestingEngineerDid" "attestingRobots"]]
      (is (contains? req field)))))

;; ── G9 — parcel biodiversity-no-harm + LANDS registry (greenfield Council-gated) ──
(deftest test-g9-parcel-biodiversity-lands
  (let [doc (lex "parcelEnergyAttestation")
        req (required-union doc)]
    (doseq [field ["biodiversityNoHarmAttestationCid" "landsRegistryCid" "parcelClass"]]
      (is (contains? req field)))
    (is (contains? (known doc "parcelClass") "greenfield-council-attested"))))

;; ── generation provenance: signed inverters ──
(deftest test-generation-signed-inverters
  (is (contains? (required-union (lex "generationRecord")) "signingInverterDids")))

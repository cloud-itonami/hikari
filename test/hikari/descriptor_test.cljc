(ns hikari.descriptor-test
  "hikari 光 — descriptor cross-checks: the facets that describe this actor must
  describe the SAME actor.

    manifest.jsonld   constitutionalGates G1–G14, constitutionalInvariants, cells, lexiconNamespaces, id
    manifest.edn      :actor/cells, :actor/gates, :actor/lex (EDN tx-data; nested values are pr-str blobs)
    cells/*.edn       :cell/id, :cell/handler, :cell/gates
    lex/*.edn         canonical lexicon entities
    lexicons/**/*.json  the EAVT wire projection of lex/*.edn (lexicon-projection.edn)
    hikari.murakumo   cell-specs / actor-did — the cljc actor boundary
    hikari.methods.agent  the handlers cells/*.edn name

  Each facet is valid on its own; what breaks silently is the SEAM — a cell added to
  manifest.jsonld but not to cell-specs is simply absent from all-cell-plans, a
  handler renamed only in cells/*.edn is a runtime `function not found`, a lexicon
  re-projected from a stale EDN carries the old schema on the wire. Nothing checked
  these seams before 2026-09-11.

  Replaces methods/test_charter_gates.cljc, which read the etzhayyim monorepo's
  `00-contracts/lexicons/...` through java.io.File + cheshire and asserted a
  knownValues vocabulary (componentType / magnetAttestation / chemistryAttestation)
  that the lexicons in THIS repo never carried. Its one assertion that holds here —
  the full G1–G14 gate set — is kept as the first test below.

  Reads files relative to the repo root (cwd), the way `nbb run_tests.cljs` and
  `clojure -M:test` are both invoked."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [kotoba.lang.text :as str]
            [hikari.murakumo :as m]
            [hikari.methods.agent :as agent]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:clj [clojure.data.json :as json] :cljs ["node:fs" :as fs])))

;; ── portable IO ─────────────────────────────────────────────────────────────
(defn- read-text [rel] #?(:clj (slurp rel) :cljs (fs/readFileSync rel "utf8")))
(defn- read-json [rel]
  #?(:clj (json/read-str (read-text rel))
     :cljs (js->clj (js/JSON.parse (read-text rel)))))
(defn- read-entity
  "First entity of an EDN tx-data vector (all descriptors here are 1-entity files)."
  [rel]
  (first (edn/read-string (read-text rel))))
(defn- blob
  "manifest.edn keeps nested values as pr-str strings (edn-datomize house style)."
  [s] (edn/read-string s))

;; ── the facets ──────────────────────────────────────────────────────────────
(def cell-names ["solar_pv_install" "storage_battery" "grid_edge" "geothermal_micro" "consumption_audit"])
(def lex-names ["parcelEnergyAttestation" "installAttestation" "generationRecord"
                "consumptionAuditRecord" "silenEnergyReview"])

(defn- jsonld [] (read-json "manifest.jsonld"))
(defn- manifest [] (read-entity "manifest.edn"))
(defn- cell-edn [n] (read-entity (str "cells/" n ".edn")))
(defn- lex-edn [n] (read-entity (str "lex/" n ".edn")))
(defn- lex-json [n] (first (read-json (str "lexicons/com/etzhayyim/hikari/" n ".json"))))

(defn- lex-attr
  "`:lex.<name>/<suffix>` on an EDN entity, or `\"lex.<name>/<suffix>\"` on its JSON
  projection. Namespaced by `lex.` on purpose — `:db/id` also ends in `id`."
  [entity suffix]
  (some (fn [[k v]]
          (let [k (if (keyword? k) (str (namespace k) "/" (name k)) (str k))]
            (when (and (str/starts-with? k "lex.") (str/ends-with? k (str "/" suffix))) v)))
        entity))

;; ── constitutional gates (kept from test_charter_gates) ─────────────────────
(deftest constitutional-gates-are-exactly-g1-to-g14
  (is (= (set (map #(str "G" %) (range 1 15)))
         (set (keys (get-in (jsonld) ["constitutionalGates" "gates"]))))))

(deftest constitutional-invariants-name-declared-gates-with-matching-text
  ;; `constitutionalInvariants` is the README's "immutable" trio. Each key is
  ;; `G<n>_<word>_...`; the gate it names must exist and its text must say the word,
  ;; so renumbering the gates map without touching the invariants goes red.
  (let [{:strs [constitutionalGates constitutionalInvariants]} (jsonld)
        gates (get constitutionalGates "gates")]
    (is (= #{"G4" "G5" "G8"}
           (set (map #(first (str/split % #"_")) (keys constitutionalInvariants))))
        "the three constitutional invariants are G4 (nuclear) / G5 (fossil) / G8 (rare-earth)")
    (doseq [k (keys constitutionalInvariants)
            :let [[gate _no word] (str/split k #"_")]]
      (is (contains? gates gate) (str k " names a gate that is not declared"))
      (is (str/includes? (str/lower (str (get gates gate))) (str/lower word))
          (str k ": gate " gate " text does not mention " word)))))

;; ── cells ───────────────────────────────────────────────────────────────────
(deftest cell-ids-agree-across-the-four-facets
  (let [from-jsonld (set (map #(get % "name") (get (jsonld) "cells")))
        from-manifest (set (map #(name (:cell/id %)) (blob (:actor/cells (manifest)))))
        from-cell-files (set (map #(str (:cell/id (cell-edn %))) cell-names))
        from-boundary (set (map name (keys m/cell-specs)))]
    (is (pos? (count from-jsonld)) "evidence floor: at least one cell is declared")
    (is (= from-jsonld from-manifest) "manifest.jsonld cells ≠ manifest.edn :actor/cells")
    (is (= from-jsonld from-cell-files) "manifest.jsonld cells ≠ cells/*.edn")
    (is (= from-jsonld from-boundary) "manifest.jsonld cells ≠ hikari.murakumo/cell-specs")
    (doseq [c (get (jsonld) "cells")]
      (is (= (str "hikari.cells." (get c "name")) (get c "module"))
          (str "cell " (get c "name") " module is not hikari.cells.<name>")))))

(deftest every-cell-handler-exists-in-the-agent
  ;; cells/*.edn names its handler in snake_case (the Python era); the port is the
  ;; kebab-case public fn of the same name in hikari.methods.agent. A handler renamed
  ;; on one side only is a valid descriptor and a runtime `function not found`.
  (let [publics (ns-publics 'hikari.methods.agent)]
    (doseq [n cell-names
            :let [handler (:cell/handler (cell-edn n))
                  sym (symbol (str/replace handler "_" "-"))
                  v (get publics sym)]]
      (is (string? handler) (str n ": :cell/handler missing"))
      (is (some? v) (str n ": handler " handler " → " sym " is not a public fn of hikari.methods.agent"))
      (when v
        (testing (str n ": " sym " is a state-map handler")
          ;; handlers thread the state map through (Python `state.update(...)`);
          ;; grid_edge computes from defaults, the other four refuse with :error —
          ;; either way the caller's keys survive.
          (let [out (@v {:hikari.descriptor-test/probe n})]
            (is (map? out) "a handler returns a state map")
            (is (= n (:hikari.descriptor-test/probe out)) "the input state is threaded through, not replaced")))))))

(deftest cell-gates-are-declared-actor-gates
  (let [declared (set (map :gate/id (blob (:actor/gates (manifest)))))]
    (is (pos? (count declared)) "evidence floor: manifest.edn declares gates")
    (doseq [n cell-names
            :let [gates (:cell/gates (cell-edn n))]]
      (is (seq gates) (str n ": a cell with no gates is ungoverned"))
      (is (set/subset? (set gates) declared)
          (str n ": gates " (pr-str (remove declared gates)) " are not in manifest.edn :actor/gates")))))

;; ── lexicons ────────────────────────────────────────────────────────────────
(deftest lexicons-agree-across-manifest-lex-and-wire
  (let [from-jsonld (set (get (jsonld) "lexiconNamespaces"))
        from-manifest (set (map #(str "com.etzhayyim.hikari." (:lex/id %)) (blob (:actor/lex (manifest)))))
        from-lex (set (map #(lex-attr (lex-edn %) "id") lex-names))
        from-wire (set (map #(lex-attr (lex-json %) "id") lex-names))]
    (is (pos? (count from-jsonld)) "evidence floor: at least one lexicon is declared")
    (is (= from-jsonld from-manifest) "manifest.jsonld lexiconNamespaces ≠ manifest.edn :actor/lex")
    (is (= from-jsonld from-lex) "manifest.jsonld lexiconNamespaces ≠ lex/*.edn ids")
    (is (= from-jsonld from-wire) "manifest.jsonld lexiconNamespaces ≠ lexicons/**/*.json ids")))

(deftest wire-lexicons-are-the-projection-of-the-canonical-edn
  ;; lexicon-projection.edn: canonical-source lex/*.edn → wire-output lexicons/**/*.json.
  ;; A re-projection skipped after an EDN edit leaves the old schema on the wire.
  (doseq [n lex-names
          :let [e (lex-edn n) j (lex-json n)]]
    (is (= (lex-attr e "id") (lex-attr j "id")) (str n ": id differs between lex/ and lexicons/"))
    (is (= (lex-attr e "lexicon") (lex-attr j "lexicon")) (str n ": lexicon version differs"))
    (is (= (lex-attr e "defs") (lex-attr j "defs")) (str n ": defs differ — lexicons/ was not re-projected from lex/"))
    (is (str/includes? (str (lex-attr e "defs")) ":required") (str n ": defs carries no :required — not a record schema"))))

;; ── identity ────────────────────────────────────────────────────────────────
(deftest actor-did-agrees-between-manifest-and-boundary
  (is (= (get (jsonld) "id") m/actor-did) "manifest.jsonld id ≠ hikari.murakumo/actor-did")
  (is (= (:actor/id (manifest)) (last (str/split m/actor-did #":")))
      "manifest.edn :actor/id is the DID's last segment"))

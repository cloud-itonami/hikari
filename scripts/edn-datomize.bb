#!/usr/bin/env kbb
;; scripts/edn-datomize.bb — EDN → Datomic/Datascript tx-data 変換ツール（hikari 用に
;; com-junkawasaki/root superproject の manifest/edn-datomize.bb から移植・拡張）。
;;
;; 「datomic/datascript query 可能」の定義: ファイルのトップレベルが
;; (d/transact conn (edn/read-string (slurp file))) にそのまま渡せる
;; tx-data ベクタ（entity-map のベクタ、各 map は :db/id を持つ）であること。
;;
;; マップ1個のファイルは [{...:db/id -1}] に包む。キーが既に意味のある名前空間
;; （例 :cell/id、:actor/id）を持っていればそのまま使い、bare なキーだけ
;; ファイル由来の名前空間を付与する（wrap-ns）。素の bare-key マップ（AT-Proto
;; lexicon の :lexicon/:id/:defs 等）はファイル由来の名前空間で全キーを prefix
;; する（wrap-map）。既にベクタ-of-namespaced-entity 形式のファイルは各 entity に
;; :db/id を足すだけ（wrap-vec）。値が Datomic の scalar valueType
;; （string/long/double/boolean/keyword、またはそれらの集合）に収まらないもの
;; （入れ子 map、map を含む vector 等）は pr-str した文字列として保持する
;; （valueType=string の "blob" 属性にする）。属性定義は repo root の schema.edn
;; に自動登録する（Datomic/Datascript 両対応）。
;;
;; 使い方:
;;   bb scripts/edn-datomize.bb wrap-map <path> <ns>   — bare-key map 1個を ns で prefix
;;   bb scripts/edn-datomize.bb wrap-ns  <path> <ns>   — 既存の namespaced key は保持、bare だけ ns で prefix
;;   bb scripts/edn-datomize.bb wrap-vec <path>        — 既に vector-of-namespaced-entity のファイルへ :db/id を付与

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn schema-path [] (io/file root "schema.edn"))

(defn slurp-edn [path] (edn/read-string (slurp path)))

(defn already-tx-data?
  "既に [{...:db/id ...} ...] 形式に変換済みか判定（再実行の冪等性用）。"
  [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

(defn classify
  "値から Datomic :db/valueType + :db/cardinality を推定する。scalar に収まらない
   値（入れ子 map / map を含む vector 等）は :blob true を返す(pr-str して string 化)。"
  [v]
  (cond
    (string? v)  {:type :db.type/string  :card :db.cardinality/one}
    (boolean? v) {:type :db.type/boolean :card :db.cardinality/one}
    (integer? v) {:type :db.type/long    :card :db.cardinality/one}
    (double? v)  {:type :db.type/double  :card :db.cardinality/one}
    (keyword? v) {:type :db.type/keyword :card :db.cardinality/one}
    (nil? v)     {:type :db.type/string  :card :db.cardinality/one}
    (and (coll? v) (empty? v))
    {:type :db.type/string :card :db.cardinality/many}
    (and (coll? v) (every? string? v))  {:type :db.type/string  :card :db.cardinality/many}
    (and (coll? v) (every? keyword? v)) {:type :db.type/keyword :card :db.cardinality/many}
    (and (coll? v) (every? integer? v)) {:type :db.type/long    :card :db.cardinality/many}
    (and (coll? v) (every? double? v))  {:type :db.type/double  :card :db.cardinality/many}
    :else {:type :db.type/string :card :db.cardinality/one :blob true}))

(defn attr-value [v]
  (let [{:keys [blob]} (classify v)]
    (if blob (pr-str v) v)))

(defn namespaced-key [ns-name k]
  (keyword ns-name (name k)))

(defn preserve-key
  "既に namespace を持つキーはそのまま、bare なキーだけ fallback-ns を付与する。"
  [fallback-ns k]
  (if (namespace k) k (namespaced-key fallback-ns k)))

(defn entity-from-map
  "トップレベル map の各キーに ns-name の名前空間を付け、:db/id を足した 1 entity にする
   （全キーを prefix。bare-key ファイル用）。"
  [content ns-name]
  (into {:db/id -1}
        (map (fn [[k v]] [(namespaced-key ns-name k) (attr-value v)]))
        content))

(defn entity-from-map-preserve
  "トップレベル map の既存 namespace は保持し、bare なキーだけ fallback-ns を付与した
   :db/id 付き 1 entity にする（既に idiomatic に namespaced なファイル用）。"
  [content fallback-ns]
  (into {:db/id -1}
        (map (fn [[k v]] [(preserve-key fallback-ns k) (attr-value v)]))
        content))

(defn schema-attrs [entity]
  (for [[k v] (dissoc entity :db/id)]
    (let [{:keys [type card]} (classify v)]
      {:db/ident k :db/valueType type :db/cardinality card})))

(defn load-schema []
  (let [f (schema-path)]
    (if (.exists f) (slurp-edn f) [])))

(defn merge-schema! [new-attrs]
  (let [existing (load-schema)
        by-ident (into {} (map (juxt :db/ident identity)) existing)
        merged-by-ident (reduce (fn [acc {:keys [db/ident] :as attr}]
                                   (if (contains? acc ident) acc (assoc acc ident attr)))
                                 by-ident
                                 new-attrs)
        merged (vec (sort-by (comp str :db/ident) (vals merged-by-ident)))]
    (spit (schema-path) (str ";; schema.edn — Datomic/Datascript 互換スキーマ定義（自動生成 by scripts/edn-datomize.bb）\n"
                              ";; :db/ident 属性定義のリスト。Datomic 固有キー(:db.install/_attribute 等)は使わない。\n"
                              ";; 手編集禁止 — 再生成すると上書きされる。\n\n"
                              (pr-str merged)
                              "\n"))
    merged))

(defn wrap-map! [rel-path ns-name]
  (let [f (io/file root rel-path)
        content (slurp-edn f)]
    (if (already-tx-data? content)
      (println "skip (already tx-data):" rel-path)
      (let [entity (entity-from-map content ns-name)]
        (spit f (pr-str [entity]))
        (merge-schema! (schema-attrs entity))
        (println "wrapped (wrap-map)" rel-path "->" (dec (count entity)) "attrs, ns=" ns-name)))))

(defn wrap-ns! [rel-path fallback-ns]
  (let [f (io/file root rel-path)
        content (slurp-edn f)]
    (if (already-tx-data? content)
      (println "skip (already tx-data):" rel-path)
      (let [entity (entity-from-map-preserve content fallback-ns)]
        (spit f (pr-str [entity]))
        (merge-schema! (schema-attrs entity))
        (println "wrapped (wrap-ns)" rel-path "->" (dec (count entity)) "attrs, fallback-ns=" fallback-ns)))))

(defn wrap-vec! [rel-path]
  (let [f (io/file root rel-path)
        content (slurp-edn f)]
    (if (already-tx-data? content)
      (println "skip (already tx-data):" rel-path)
      (if-not (and (vector? content) (every? map? content))
        (println "skip (not a vector-of-maps):" rel-path)
        (let [entities (vec (map-indexed
                              (fn [i m]
                                (into {:db/id (- (inc i))}
                                      (map (fn [[k v]] [k (attr-value v)]))
                                      m))
                              content))]
          (spit f (pr-str entities))
          (merge-schema! (distinct (mapcat schema-attrs entities)))
          (println "wrapped (wrap-vec)" rel-path "->" (count entities) "entities"))))))

(defn -main [& args]
  (let [[mode a b] args]
    (case mode
      "wrap-map" (wrap-map! a b)
      "wrap-ns"  (wrap-ns! a b)
      "wrap-vec" (wrap-vec! a)
      (do (println "usage: bb scripts/edn-datomize.bb [wrap-map <path> <ns> | wrap-ns <path> <fallback-ns> | wrap-vec <path>]")
          (System/exit 1)))))

(apply -main *command-line-args*)

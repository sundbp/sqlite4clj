(ns indexing-edn-blob
  (:require [sqlite4clj.core :as d]))

(defonce db
  (d/init-db! "database.db"
    {:read-only true
     :pool-size 4
     :pragma    {:foreign_keys false}}))

(def reader  (db :reader))
(def writer (db :writer))

(defn entity-type [blob]
  (-> blob :type))

(comment
  (d/q writer
    ["CREATE TABLE IF NOT EXISTS entity(id TEXT PRIMARY KEY, data BLOB) WITHOUT ROWID"])

  (d/create-function db "entity_type" #'entity-type {:deterministic? true})

  (d/q writer
    ["CREATE INDEX IF NOT EXISTS entity_type_idx ON entity(entity_type(data))
    WHERE entity_type(data) IS NOT NULL"])

  (d/q writer
    ["INSERT INTO entity (id, data) VALUES (?, ?)"
     (str (random-uuid))
     {:type "foo" :a (rand-int 10) :b (rand-int 10)}])

  (d/q writer
    ["INSERT INTO entity (id, data) VALUES (?, ?)"
     (str (random-uuid))
     {:a (rand-int 10) :b (rand-int 10)}])

  (d/q reader
    ["select * from entity where entity_type(data) = ?" "foo"])

  ;; Check index is being used
  (d/q reader
    ["explain query plan select * from entity where entity_type(data) = ?" "foo"])
  ;; -> [[3 0 62 "SEARCH entity USING INDEX entity_type_idx (<expr>=?)"]]


  )









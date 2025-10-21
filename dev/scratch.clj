(ns scratch
  (:require [sqlite4clj.core :as d]
            [clj-async-profiler.core :as prof]
            [fast-edn.core :as edn])
  (:import (java.util.concurrent Executors)))

;; Make futures use virtual threads
(set-agent-send-executor!
  (Executors/newVirtualThreadPerTaskExecutor))

(set-agent-send-off-executor!
  (Executors/newVirtualThreadPerTaskExecutor))

(defonce db
  (d/init-db! "database.db"
    {:read-only  true
     :pool-size  4
     :pragma     {:foreign_keys false}}))

(def reader  (db :reader))
(def writer (db :writer))

(comment

  (d/q reader ["pragma mmap_size;"])
  (d/q reader ["pragma cache_size;"])

  (->>
    (d/q reader ["PRAGMA compile_options;"])
    (filter #(re-find #"MAX_" %)))


  (d/with-read-tx [tx reader]
    (d/q tx ["pragma foreign_keys;"])
    (d/q tx ["pragma foreign_keys;"]))

  (d/q reader ["some malformed sqlite"])

  (d/q reader ["pragma wal;" "pragma wal;"])

  (time
    (->> (d/q reader ["SELECT chunk_id, state FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                      1978 3955 5932 1979 3956 5933 1980 3957 5934])
      (mapv
        (fn [[chunk-id state]] {:chunk-id chunk-id :state state}))))

  (d/q reader ["INSERT INTO session (id, checks) VALUES ('foo', 1)"])

  (time
    (->> (mapv
           (fn [n]
             (future
               (d/q reader
                 ["SELECT chunk_id, JSON_GROUP_ARRAY(state) AS chunk_cells FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)  GROUP BY chunk_id" 1978 3955 5932 1979 3956 5933 1980 3957 5934])))
           (range 0 2000))
      (run! (fn [x] @x))))

  (user/bench
    (->> (mapv
           (fn [n]
             (future
               (do
                 (d/q reader ["SELECT chunk_id FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                              (+ n 1978)
                              (+ n 3955)
                              (+ n 5932)
                              (+ n 1979)
                              (+ n 956)
                              (+ n 5933)
                              (+ n 1980)
                              (+ n 3957)
                              (+ n 5934)])
                 nil)))
           (range 0 4000))
      (run! (fn [x] @x))))

  (user/bench
    ;; Execution time mean : 455.139383 µs
    ;; Execution time mean : 345.804480 µs
    ;; Execution time mean : 187.652161 µs
    (d/q reader
      ["SELECT chunk_id, state FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       1978 3955 5932 1979 3956 5933 1980 3957 5934]))

  (user/bench
    (->> ;; Execution time mean : 131.101111 µs
      (d/q reader
        ["SELECT chunk_id FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)"
         1978 3955 5932 1979 3956 5933 1980 3957 5934])))

  (user/bench
    (->> (d/q reader
           ["SELECT chunk_id, state FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            1978 3955 5932 1979 3956 5933 1980 3957 5934])
      (mapv (fn [[chunk-id state]] {:chunk-id chunk-id :state state}))))

  (user/bench
    (d/q reader
      ["SELECT chunk_id, JSON_GROUP_ARRAY(state) AS chunk_cells FROM cell WHERE chunk_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?)  GROUP BY chunk_id" 1978 3955 5932 1979 3956 5933 1980 3957 5934]))

  (d/q reader ["pragma query_only"])
  (d/q writer ["pragma query_only"])

  (count (:conn-pool reader))

  (d/optimize-db reader)
  (d/optimize-db writer)

  "134"

  (d/q db ["SELECT checks FROM session WHERE id = ?" "134"])
  (d/q db ["SELECT checks FROM session WHERE id = ?" "134"])
  (d/q db ["SELECT checks FROM session WHERE id = ?" 34])

  (d/q db ["SELECT checks FROM session WHERE id = ?" "foo"])
  (d/q db ["SELECT id FROM session"])

  (d/q writer ["SELECT * FROM session WHERE id = ?" "bar"])
  (+ 3 4)
  (time
    (d/q writer
      ["INSERT INTO session (id, checks) VALUES (?, ?)" "stro" 1]))

  )

(comment
  (def sid "someid")

  (defn test-case [db sid]
    (let [[checks] (d/q db ["SELECT checks FROM session WHERE id = ?" sid])]
      (if checks
        (d/q db ["UPDATE session SET checks = ? WHERE id = ?" checks sid])
        (d/q db ["INSERT INTO session (id, checks) VALUES (?, ?)" sid 1]))))

  (do
    (test-case writer sid)
    (test-case writer sid)
    (d/q writer ["SELECT checks FROM session WHERE id = ?" sid]))

  (d/with-write-tx [tx writer]
    (test-case tx sid)
    (test-case tx sid)
    (d/q tx ["SELECT checks FROM session WHERE id = ?" sid])))

(comment
  (d/q writer
    ["CREATE TABLE IF NOT EXISTS blobby(id TEXT PRIMARY KEY, data BLOB) WITHOUT ROWID"])

  (d/q writer
    ["INSERT INTO blobby (id, data) VALUES (?, ?)"
     "blob-test4"
     (String/.getBytes "hello there")])

  (String/.getBytes "blob-test1")

  (count (String/.getBytes "hello there" "UTF-8"))

  (type (first (d/q reader ["SELECT data FROM blobby WHERE id = ?" "blob-test2"])))
  (d/q reader ["SELECT id, data FROM blobby WHERE id = ?" "blob-test4"])

  (d/q writer
    ["INSERT INTO blobby (id, data) VALUES (?, ?)"
     (str (random-uuid))
     ["some encoded blob"]])

  (d/q reader ["SELECT id, data FROM blobby"])

  (d/q reader ["SELECT id, data FROM blobby WHERE id = ?" "blob-test5"])
  )

(comment ;; encoded blob vs regular table

  (d/q writer
    ["CREATE TABLE IF NOT EXISTS test_1(id INT PRIMARY KEY, data BLOB)"])
  (run!
    (fn [id]
      (d/q writer
        ["INSERT INTO test_1 (id, data) VALUES (?, ?)"
         id
         {:id       id :email "bob@foobar.com"
          :username "escalibardarian"}]))
    (range 0 100))

  (d/q writer
    ["CREATE TABLE IF NOT EXISTS test_2(id INT PRIMARY KEY, email TEXT , username TEXT)"])
  (run!
    (fn [id]
      (d/q writer
        ["INSERT INTO test_2 (id, email, username) VALUES (?, ?, ?)"
         id "bob@foobar.com" "escalibardarian"]))
    (range 0 100))

  (d/q writer
    ["CREATE TABLE IF NOT EXISTS test_3(id INT PRIMARY KEY, data TEXT)"])
  (run!
    (fn [id]
      (d/q writer
        ["INSERT INTO test_3 (id, data) VALUES (?, ?)"
         id (str {:id       id :email "bob@foobar.com"
                  :username "escalibardarian"})]))
    (range 0 100))

  (d/q writer
    ["CREATE TABLE IF NOT EXISTS test_4(id INT PRIMARY KEY, data BLOB)"])
  (run!
    (fn [id]
      (d/q writer
        ["INSERT INTO test_4 (id, data) VALUES (?, ?)"
         id (String/.getBytes
              (str {:id       id :email "bob@foobar.com"
                    :username "escalibardarian"}))]))
    (range 0 100))

  (user/bench
    ;; 1.206098 ms
    ;; 238.893356 µs
    ;; 61.222818 µs
    ;; 58.882514 µs
    ;; 54.009261 µs
    ;; 52.006687 µs
    ;; 51.460267 µs
    (d/q reader ["select data from test_1"]))

  (user/bench ;; 20.496617 µs
    (->> (d/q reader ["select * from test_2"])
      (mapv (fn [[id email username]]
              {:id id :email email :username username}))))

  ;; Raw get bytes
  (user/bench
    ;; 18.753884 µs
    (->> (d/q reader ["select * from test_4"])))

  ;; Raw get text
  (user/bench ;; 12.411071 µs
    (d/q reader ["select data from test_3"]))

  (user/bench ;; 43.204357 µs
    (->> (d/q reader ["select data from test_3"])
      (mapv edn/read-string)))

  ;; Concat madness
  (user/bench ;; 30.865782 µs
    (->> (d/q reader ["select concat('[', group_concat(data), ']') from test_3 limit 1"])
      first
      edn/read-string))

  )

(comment ;; Profiling
  (prof/start)
  (prof/stop)
  (prof/serve-ui 7777)
  ;; (clojure.java.browse/browse-url "http://localhost:7777/")
  )

(comment
  (defn double [v] (* 2 v))
  (d/create-function db "double" double {:deterministic? true})
  (d/q reader ["SELECT double(5)"])
  ;; => [10]
  (d/q writer ["SELECT double(5)"])
  ;; => [10]

  (d/create-function db "double_var" #'double {:deterministic? true})
  (d/q reader ["SELECT double_var(5)"])
  ;; => [10]
  ;; now change and re-eval double and see magic!
  ;;
  )

  

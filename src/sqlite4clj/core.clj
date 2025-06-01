(ns sqlite4clj.core
  "High level interface for using sqlite4clj including connection pool
  and prepared statement caching."
  (:require
   [sqlite4clj.api :as api]
   [clojure.string :as str]
   [clojure.core.cache.wrapped :as cache])
  (:import
   (java.util.concurrent BlockingQueue LinkedBlockingQueue)))

(defn type->sqlite3-bind [param]
  (cond
    (integer? param) `api/bind-int
    (double? param)  `api/bind-double
    :else            `api/bind-text))

(defmacro build-bind-fn [first-run-params]
  (let [stmt   (gensym)
        params (gensym)]
    `(fn [~stmt ~params]
       ~@(map-indexed
           (fn [i param]
             ;; starts at 1
             `(~(type->sqlite3-bind param) ~stmt ~(inc i)
               (~params ~i))) first-run-params))))

(defn col-type->col-fn [sqlite-type]
  (case (int sqlite-type)
    (1 5) `api/column-int
    (4)   `api/column-double
    `api/column-text))

(defn get-column-types [stmt]
  (let [n-cols (int (api/column-count stmt))]
    (mapv (fn [n] (api/column-type stmt n)) (range 0 n-cols))))

(defmacro build-column-fn [column-types]
  (let [stmt (gensym)]
    `(fn [~stmt]
       ~(if (= (count column-types) 1)
          ;; Unwrap when returning single column
          `(~(col-type->col-fn (first column-types)) ~stmt 0)
          (vec (map-indexed
                 (fn [i n] `(~(col-type->col-fn n) ~stmt ~i))
                 column-types))))))

(defn prepare
  ([pdb sql params]
   (let [stmt (api/prepare-v3 pdb sql)]
     (cond-> {:stmt stmt}
       (seq params) (assoc :bind-fn (eval `(build-bind-fn ~params)))))))

(defn prepare-cached [{:keys [pdb stmt-cache]} query]
  (let [sql    (first query)
        params (subvec query 1)
        {:keys [stmt bind-fn] :as m}
        (cache/lookup-or-miss stmt-cache sql
          (fn [_] (prepare pdb sql params)))]
    (when bind-fn (bind-fn stmt params))
    m))

(defmacro with-stmt-reset
  {:clj-kondo/lint-as 'clojure.core/with-open}
  [[stmt-binding stmt] & body]
  `(let [~stmt-binding ~stmt]
     (try
       ~@body
       (finally
         (api/reset ~stmt-binding)
         (api/clear-bindings ~stmt-binding)))))

(defn step-rows [stmt col-fn rows]
  (loop [rows (transient rows)]
    (let [code (int (api/step stmt))]
      (case code
        100 (recur (conj! rows (col-fn stmt)))
        101 (persistent! rows)
        code))))

(defn- execute-q [conn [sql :as query]]
  (let [{:keys [stmt col-fn]} (prepare-cached conn query)]
    (with-stmt-reset [stmt stmt]
      (if col-fn
        (step-rows stmt col-fn [])
        (let [stmt-cache (:stmt-cache conn)
              code       (int (api/step stmt))]
          (if (= 100 code)
            (let [col-types (get-column-types stmt)
                  col-fn    (eval `(build-column-fn ~col-types))]
              (swap! stmt-cache assoc-in [sql :col-fn] col-fn)
              (step-rows stmt col-fn [(col-fn stmt)]))
            (do (swap! stmt-cache assoc-in [sql :col-fn] (fn noop [_]))
                code)))))))

(defn q* [conn query]
  (let [result (execute-q conn query)]
    (cond
      (vector? result) result
      (= result 101)   []
      :else            (throw (api/sqlite-ex-info (:pdb conn) result
                                {:sql    (first query)
                                 :params (subvec query 1)})))))

(def default-pragma
  {:cache_size   15625
   :page_size    4096
   :journal_mode "WAL"
   :synchronous  "NORMAL"
   :temp_store   "MEMORY"
   :foreign_keys true})

(defn pragma->set-pragma-query [pragma]
  (->> (merge default-pragma pragma)
    (map (fn [[k v]] (str "pragma " (name k) "=" v ";")))
    str/join))

(defn new-conn! [db-name pragma read-only]
  (let [flags           (if read-only
                          ;; SQLITE_OPEN_READONLY
                          0x00000001
                          ;; SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
                          (bit-or 0x00000002 0x00000004))
        *pdb            (api/open-v2 db-name flags)
        statement-cache (cache/fifo-cache-factory {} :threshold 512)
        conn            {:pdb        *pdb
                         :stmt-cache statement-cache}]
    (q* conn [(pragma->set-pragma-query pragma)])
    conn))

(defn init-db!
  [db-name & [{:keys [pool-size pragma read-only]
               :or   {pool-size 4}}]]
  (let [conns (repeatedly pool-size
                (fn [] (new-conn! db-name pragma read-only)))
        pool  (LinkedBlockingQueue/new ^int pool-size)]
    (run! #(BlockingQueue/.add pool %) conns)
    {:conn-pool pool
     :close
     (fn [] (run! (fn [conn] (api/close (:pdb conn))) conns))}))

(defn q [{:keys [conn-pool] :as tx} query]
  (if conn-pool
    (let [conn (BlockingQueue/.take conn-pool)]
      (try
        (q* conn query)
        ;; Always return the conn even on error
        (finally (BlockingQueue/.offer conn-pool conn))))
    ;; If we don't have a connection pool then we have a tx.
    (q* tx query)))

(defmacro with-read-tx
  {:clj-kondo/lint-as 'clojure.core/with-open}
  [[tx db] & body]
  `(let [conn-pool# (:conn-pool ~db)
         ~tx        (BlockingQueue/.take conn-pool#)]
     (try
       (q ~tx ["BEGIN DEFERRED"])
       ~@body
       (finally
         (q ~tx ["COMMIT"])
         (BlockingQueue/.offer conn-pool# ~tx)))))

(defmacro with-write-tx
  {:clj-kondo/lint-as 'clojure.core/with-open}
  [[tx db] & body]
  `(let [conn-pool# (:conn-pool ~db)
         ~tx        (BlockingQueue/.take conn-pool#)]
     (try
       (q ~tx ["BEGIN IMMEDIATE"])
       ~@body
       (finally
         (q ~tx ["COMMIT"])
         (BlockingQueue/.offer conn-pool# ~tx)))))

;; WAL + single writer enforced at the application layer means you don't need
;; to handle SQLITE_BUSY or SQLITE_LOCKED.

;; TODO: finalise prepared statements when shutting down


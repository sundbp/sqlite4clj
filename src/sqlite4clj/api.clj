(ns sqlite4clj.api
  "These function map directly to SQLite's C API."
  (:require
   [clojure.java.io :as io]
   [coffi.ffi :as ffi :refer [defcfn]]
   [coffi.mem :as mem]) 
  (:import
   [java.nio.file Files]))

(defn copy-resource [resource-path output-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (io/file output-path))]
    (io/copy in out)))

;; Load appropriate SQLite library
(let [arch              (System/getProperty "os.arch")
      res-file          ({"aarch64" "sqlite3_aarch64.so"
                          "amd64"   "sqlite3_amd64.so"
                          "x86_64"  "sqlite3_amd64.so"}
                     arch)
      temp-lib-filename (str "sqlite4clj_temp_" res-file)]
  (copy-resource res-file temp-lib-filename)
  (ffi/load-library temp-lib-filename)
  ;; We delete once loaded
  (Files/deleteIfExists (.toPath (io/file temp-lib-filename))))

(defcfn initialize
  sqlite3_initialize
  [::mem/pointer] ::mem/int)

(defonce init-lib (initialize nil))

(defcfn errmsg
  sqlite3_errmsg
  [::mem/pointer] ::mem/c-string)

(defcfn errstr
  sqlite3_errstr
  [::mem/int] ::mem/c-string)

(defn sqlite-ex-info [pdb code data]
  (let [code-name (errstr code)
        message   (errmsg pdb)]
    (ex-info (str "SQLite error: " code-name "\n" message)
      (assoc data
        :code code-name
        :message message))))

(defn sqlite-ok? [code]
  (= code 0))

(defcfn open-v2
  "sqlite3_open_v2" [::mem/c-string ::mem/pointer ::mem/int
                     ::mem/c-string] ::mem/int
  sqlite3-open-native
  [filename flags]
  (with-open [arena (mem/confined-arena)]
    (let [pdb           (mem/alloc-instance ::mem/pointer arena)
          filename-utf8 (String/new (String/.getBytes filename "UTF-8") "UTF-8")
          code          (sqlite3-open-native filename-utf8
                 pdb flags nil)]
      (if (sqlite-ok? code)
        (mem/deserialize-from pdb ::mem/pointer)
        (throw (sqlite-ex-info pdb code {:filename filename}))))))

(defcfn close
  sqlite3_close
  [::mem/pointer] ::mem/int)

(defcfn prepare-v3
  "sqlite3_prepare_v3"
  [::mem/pointer ::mem/c-string ::mem/int
   ::mem/int
   ::mem/pointer ::mem/pointer] ::mem/int
  sqlite3-prepare-native
  [pdb sql]
  (with-open [arena (mem/confined-arena)]
    (let [ppStmt (mem/alloc-instance ::mem/pointer arena)
          sql    (String/new (String/.getBytes sql "UTF-8") "UTF-8")
          code   (sqlite3-prepare-native pdb sql -1
                   0x01 ;; SQLITE_PREPARE_PERSISTENT
                   ppStmt
                   nil)]
      (if (sqlite-ok? code)
        (mem/deserialize-from ppStmt ::mem/pointer)
        (throw (sqlite-ex-info pdb code {:sql sql}))))))

(defcfn reset
  sqlite3_reset
  [::mem/pointer] ::mem/int)

(defcfn clear-bindings
  sqlite3_clear_bindings
  [::mem/pointer] ::mem/int)

(defcfn bind-int
  sqlite3_bind_int
  [::mem/pointer ::mem/int ::mem/int] ::mem/int)

(defcfn bind-double
  sqlite3_bind_double
  [::mem/pointer ::mem/int ::mem/double] ::mem/int)

(def sqlite-static (mem/as-segment 0))
(def sqlite-transient (mem/as-segment -1))

(defcfn bind-text
  "sqlite3_bind_text"
  [::mem/pointer ::mem/int ::mem/c-string ::mem/int
   ::mem/pointer] ::mem/int
  sqlite3-bind-text-native
  [pdb idx text]
  (let [text       (str text)
        text-bytes (String/.getBytes text "UTF-8")]
    (sqlite3-bind-text-native pdb idx
      (String/new text-bytes "UTF-8")
      (count text-bytes)
      sqlite-transient)))

(defcfn step
  sqlite3_step
  [::mem/pointer] ::mem/int)

(defcfn column-count
  sqlite3_column_count
  [::mem/pointer] ::mem/int)

(defcfn column-double 
  sqlite3_column_double
  [::mem/pointer ::mem/int] ::mem/double)

(defcfn column-int 
  sqlite3_column_int
  [::mem/pointer ::mem/int] ::mem/int)

(defcfn column-text
  sqlite3_column_text
  [::mem/pointer ::mem/int] ::mem/c-string)

(defcfn column-bytes
  sqlite3_column_bytes
  [::mem/pointer ::mem/int] ::mem/int)

(defcfn column-type
  sqlite3_column_type
  [::mem/pointer ::mem/int] ::mem/int)

(defcfn finalize
  sqlite3_finalize
  [::mem/pointer] ::mem/int)

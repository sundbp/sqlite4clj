(ns sqlite4clj.impl.encoding
  (:require [fast-edn.core :as edn])
  (:import [java.util Arrays]
           [io.airlift.compress.v3.zstd ZstdCompressor ZstdDecompressor
            ZstdNativeCompressor ZstdNativeDecompressor]))

(def RAW_BLOB (byte 0))
(def ZSTD_ENCODED_BLOB (byte 1))
(def ENCODED_BLOB (byte 2))

(def ^:dynamic *zstd-level* nil)
(def ^:dynamic *edn-readers* nil)

(defn- add-leading-byte ^byte/1 [blob leading-byte]
  (let [out (byte-array (inc (count blob)) [leading-byte])]
    (System/arraycopy blob 0 out 1 (count blob))
    out))

(defn- remove-leading-byte ^byte/1 [^byte/1 blob]
  (Arrays/copyOfRange blob 1 (count blob)))

(defn- encode-edn
  "Encode Clojure data and compress it with zstd. Compression quality
  ranges between -7 and 22 and has almost no impact on decompression speed."
  [blob]
  (assert (not= *zstd-level* nil))
  (let [blob      (binding [*print-length* nil]
                    (String/.getBytes (str blob)))
        blob-size (count blob)]
    (add-leading-byte blob ENCODED_BLOB)
    (if (> blob-size 1000)
      ;; ZSTD is only worth it when blobs get to around 1kb see:
      ;; https://github.com/facebook/zstd/issues/1134
      (let [compressor (ZstdNativeCompressor/new *zstd-level*)
            compressed
            (byte-array
              (inc (ZstdCompressor/.maxCompressedLength compressor
                     blob-size))
              [ZSTD_ENCODED_BLOB])
            compressed-size
            (ZstdCompressor/.compress compressor blob 0 blob-size
              compressed 1
              (dec (count compressed)))]
        (Arrays/copyOfRange compressed 0 (inc compressed-size)))
      ;; no compression
      (add-leading-byte blob ENCODED_BLOB))))

(defn- decode-zstd-edn
  "Decode Clojure data and decompress it with zstd."
  [blob]
  (let [decompressor (ZstdNativeDecompressor/new)
        uncompressed (byte-array
                       (ZstdDecompressor/.getDecompressedSize decompressor
                         blob 1 (dec (count blob))))]
    (ZstdDecompressor/.decompress decompressor blob 1 (dec (count blob))
      uncompressed 0 (count uncompressed))    
    (binding [*print-length* nil]
      ;; This is faster than using read-once
      (edn/read-string {:readers *edn-readers*}
        (String.  uncompressed)))))

(defn- decode-edn
  "Decode Clojure data."
  [blob]
  ;; This is faster than using read-once
  (binding [*print-length* nil]
    (edn/read-string {:readers *edn-readers*}
      (String. (remove-leading-byte blob)))))

;; -----------------------------
;; Public API

(defn encode [blob]
  (if (bytes? blob)
    (add-leading-byte blob RAW_BLOB)
    (encode-edn blob)))

(defn decode [blob size]
  (if (pos? size)
    ;; case does not work with bytes!
    (condp = (first blob)      
      ENCODED_BLOB      (decode-edn blob)
      ZSTD_ENCODED_BLOB (decode-zstd-edn blob)
      ;; Otherwise
      (remove-leading-byte blob))
    (byte-array 0)))

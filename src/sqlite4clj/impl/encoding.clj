(ns sqlite4clj.impl.encoding
  (:require [coffi.mem :as mem]
            [fast-edn.core :as edn])
  (:import [io.airlift.compress.v3.zstd
            ZstdCompressor
            ZstdDecompressor
            ZstdNativeCompressor
            ZstdNativeDecompressor]
           [java.lang.foreign MemorySegment]
           [java.util Arrays]))

(def RAW_BLOB (byte 0))
(def ZSTD_ENCODED_BLOB (byte 1))
(def ENCODED_BLOB (byte 2))

(defn- add-leading-byte ^byte/1 [blob leading-byte]
  (let [out (byte-array (inc (count blob)) [leading-byte])]
    (System/arraycopy blob 0 out 1 (count blob))
    out))

(defn- encode-edn
  "Encode Clojure data and compress it with zstd."
  [blob]
  (let [blob      (binding [*print-length* nil]
                    (String/.getBytes (str blob)))
        blob-size (count blob)]
    (add-leading-byte blob ENCODED_BLOB)
    (if (> blob-size 1000)
      ;; ZSTD is only worth it when blobs get to around 1kb see:
      ;; https://github.com/facebook/zstd/issues/1134
      ;; Compression level 3 is a good balance between speed
      ;; and compression
      (let [compressor (ZstdNativeCompressor/new 3)
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
  [^MemorySegment blob]
  ;; TODO: this compression library does not have a getDecompressedSize
  ;; function that is accessible for the MemorySegment API.
  (let [blob         (.toArray blob java.lang.foreign.ValueLayout/JAVA_BYTE)
        decompressor (ZstdNativeDecompressor/new)
        uncompressed (byte-array
                       (ZstdDecompressor/.getDecompressedSize decompressor
                         blob 0 (count blob)))]
    (ZstdDecompressor/.decompress decompressor blob 0 (count blob)
      uncompressed 0 (count uncompressed))
    ;; This is faster than using read-once
    (edn/read-string (String. uncompressed))))

(defn- decode-edn
  "Decode Clojure data."
  [^MemorySegment blob]
  ;; This is faster than using read-once
  (edn/read-string
    (.getString (.reinterpret ^MemorySegment blob Integer/MAX_VALUE) 0)))

;; -----------------------------
;; Public API

(defn encode [blob]
  (if (bytes? blob)
    (add-leading-byte blob RAW_BLOB)
    (encode-edn blob)))

(defn decode [blob size]
  (if (pos? size)
    ;; case does not work with bytes!
    (do
      (let [f-byte (mem/read-byte blob)
            blob   (mem/slice blob 1)]
        (condp = f-byte
          ENCODED_BLOB      (decode-edn blob)
          ZSTD_ENCODED_BLOB (decode-zstd-edn blob)
          ;; Otherwise
          (.toArray blob java.lang.foreign.ValueLayout/JAVA_BYTE))))
    (byte-array 0)))

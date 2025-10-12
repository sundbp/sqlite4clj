(ns sqlite4clj.litestream
  (:require [clojure.java.process :as proc]
            [clojure.java.io :as io]))

(def default-config-yml-template
  "This is a very basic yml template for using litestream with s3 compatible
  object storage. For more advanced configuration you can provide your own
  `config-yml`."
  "dbs:
    - path: %s
      replicas:
       - type: s3
         bucket:   %s
         path:     %s
         endpoint: %s
         region:   %s
         sync-interval: 1s")

(defn log-stdout [process]
  (with-open [rdr (-> process proc/stdout io/reader)]
    (run! (fn [x] (println x) (flush)) (line-seq rdr))))

(defn restore-then-replicate!
  "Throws error if litestream is not present. You want to know if your backups
  are not working. Attempts to restore db from replica if db does not already
  exist. The process is started as a JVM sub process and will be cleaned up
  when the application terminates.

  Returns the java.lang.Process that you can monitor, in the unlikely event
  that the litestream process crashes you can restart it by running
  `init-litestream!`."
  [db-name {:keys [s3-access-key-id s3-access-secret-key
                   bucket endpoint region
                   ;; This allows you to provide your own yml template from
                   ;; a file, or using a more advanced yml builder like
                   ;; clj-yaml.
                   config-yml]}]
  (let [config-file ".litestream_temp.yml"
        _           (spit config-file
                      (or config-yml
                        (format default-config-yml-template
                          db-name bucket db-name endpoint region)))
        creds       {:env {"AWS_ACCESS_KEY_ID"     s3-access-key-id
                           "AWS_SECRET_ACCESS_KEY" s3-access-secret-key}}
        restore     (proc/start creds
                      "litestream" "restore" "-config" config-file db-name)
        replicate   (proc/start creds
                      "litestream" "replicate" "-config" config-file)]
    (log-stdout restore) ;; log initial restore
    ;; (future (log-stdout replicate))
    ;; Return litestream replication process for monitoring
    replicate))

(defn restore-to-path!
  "Restore backup to a specific path. Useful for downloading backup to local-id dev environment."
  [db-name path
   {:keys [s3-access-key-id s3-access-secret-key
           bucket endpoint region config-yml]}]
  (let [config-file ".litestream_temp.yml"
        _           (spit config-file
                      (or config-yml
                        (format default-config-yml-template
                          db-name bucket db-name endpoint region)))
        creds       {:env {"AWS_ACCESS_KEY_ID"     s3-access-key-id
                           "AWS_SECRET_ACCESS_KEY" s3-access-secret-key}}
        restore     (proc/start creds
                      "litestream" "restore" "-o" path "-config" config-file db-name)]
    (log-stdout restore)))

;; TODO: build in process monitoring.

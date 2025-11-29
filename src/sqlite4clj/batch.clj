(ns sqlite4clj.batch)

(defn async-batcher-init!
  "Creates an async batching process that dynamically batches writes.

   A batch-fn operates on a batch of thunks (functions) that can be passed
   the db plus any other context (like a cache) and generally describes an
   atomic sequence of reads/writes.

   Example batch-fn function:

   (fn batch-fn [writer batch]
     (with-write-tx [db writer]
       (run! (fn [thunk] (thunk db)) batch)))

  This can be used to increase write throughput by batching writes in
  a single transaction. Use SQLite's SAVEPOINTS and ROLLBACK for logically
  nested transactions. As well as an entry point for coarse or fine grained
  subscriptions to changes."
  [db & {:keys [batch-fn return-promise? max-batch-size]
         :or   {max-batch-size  10000
                ;; If true tx! returns a promise
                return-promise? true}}]
  ;; Using an atom and subvec is similar to performance to a
  ;; ConcurrentLinkedQueue. However, it allows for a cleaner batch
  ;; function interface as you can provide more context (like a cache)
  ;; and subsequent iterations (e.g: reduce over changes).
  (assert (not (nil? batch-fn)))
  (let [batch-max-size max-batch-size
        batch_         (atom [])]
    (Thread/startVirtualThread
      (bound-fn* ;; binding conveyance
        (fn batch-thread []
          (while (not (Thread/interrupted))
            (if (= (count @batch_) 0)
              (Thread/sleep 1) ;; Allows other vthreads to run
              (let [[old-b new-b] (swap-vals! batch_
                                    (fn [batch]
                                      (subvec batch
                                        (min batch-max-size (count batch)))))]
                ;; Runs batch on virtual thread, but because there is only
                ;; one writer, this at most CPU pins a single core.
                ;; The benefit of this being a virtual thread is there's no
                ;; overhead for idle batchers so you can have multiple
                ;; sqlite db batcher without incurring overhead.
                ;; A batch-fn can always execute work on it's own CPU thread
                ;; if that's desirable.
                (batch-fn (db :writer)
                  (subvec old-b 0 (- (count old-b) (count new-b))))))))))
    (if return-promise?
      ;; Note: returns promise after the thunk is run. Not after the batch has
      ;; been committed.
      (fn tx! [thunk]
        (let [p (promise)]
          (swap! batch_ conj (comp (partial deliver p) thunk))
          p))
      (fn tx! [thunk] (swap! batch_ conj thunk)))))

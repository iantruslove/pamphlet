(ns static.io.cache
  (:require [clojure.core.cache :as cache]
            [clojure.tools.logging :as log]))

(defn new-file-cache []
  (cache/lru-cache-factory {} :threshold 128))

(defonce file-cache (atom (new-file-cache)))

;; A map of files to sets of partitions, used for cache
;; invalidation. An inverted index of sorts.
(defonce cache-partitions-to-files (atom {}))

(defn associate! [file partition]
  (swap! cache-partitions-to-files
         #(update % file (fn [part-set]
                           (conj (or part-set #{}) partition)))))

(defn cache-key [file f-miss]
  [file f-miss])

(defn read-cached-file!
  "Tries to read filename from the cache. If not in cache, f-miss will
  be called with filename as the first argument, the results of which
  will be added to the cache."
  [file f-miss]
  (let [cache-key (cache-key file f-miss)]
    (-> (if (cache/has? @file-cache cache-key)
          (do
            (log/trace "Cache hit:" cache-key)
            (cache/hit @file-cache cache-key))
          (do
            (log/trace "Cache miss:" cache-key)
            ;; Use the processing function as the partition value:
            (associate! file f-miss)
            (swap! file-cache cache/miss cache-key (f-miss file))))
        (get cache-key))))

(defn invalidate-cache! [file]
  "Invalidates all cache entries for `file`, regardless of the
  partition they are in."
  (doseq [partition (get @cache-partitions-to-files file)
          :let [cache-key (cache-key file partition)]]
    (log/debug "Invalidating cache:" cache-key)
    (swap! file-cache cache/evict cache-key)))

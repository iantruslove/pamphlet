(ns static.io.cache
  (:require [clojure.core.cache :as cache]
            [clojure.tools.logging :as log]))

(defn new-file-cache []
  (cache/lru-cache-factory {} :threshold 128))

(defonce file-cache (atom (new-file-cache)))

(defn read-cached-file
  "Tries to read filename from the cache. If not in cache, f-miss will
  be called with filename as the first argument, the results of which
  will be added to the cache."
  [[partition filename :as cache-key] f-miss]
  (-> (if (cache/has? @file-cache cache-key)
        (do
          (log/trace "Cache hit:" cache-key)
          (cache/hit @file-cache cache-key))
        (do
          (log/trace "Cache miss:" cache-key)
          (swap! file-cache cache/miss cache-key (f-miss filename))))
      (get cache-key)))

(defn invalidate-cache [cache-key]
  (log/debug "Invalidating cache:" cache-key)
  (swap! file-cache cache/evict cache-key))

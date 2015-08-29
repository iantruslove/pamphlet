(ns leiningen.pamphlet
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [static.cli :as cli]
            [static.config :as config]
            [static.core :as core]
            [static.logging :as logging]))

(defn get-config [project]
  (if (map? (:pamphlet-config project))
    (:pamphlet-config project)
    (config/load-standalone-config)))

(defn block-thread-forever []
  (loop []
    (Thread/sleep 1000)
    (recur)))

(defn ^:no-project-needed ^:pass-through-help
  pamphlet
  "pamphlet: a static website generator.

  use --help to see help."
  [project & args]
  (let [{:keys [options errors]} (cli/parse-args args)
        {show-options :options :keys [build tmp jetty watch rsync help]} options]

    (cond
      errors (println (str/join "\n" errors))
      help (println (str/join "\n" ["Pamphlet: a static blog generator."
                                    "Usage: lein pamphlet <option>:"
                                    (cli/summarize-opts)]))
      (some identity [build watch jetty rsync])
      (do (logging/setup-logging!)
          (config/init-config! (get-config project))
          (cond build (core/do-build!)
                watch (do (core/do-watch! tmp)
                          ;; Stop lein from exiting
                          (block-thread-forever))
                jetty (do (core/do-jetty! tmp)
                          ;; Stop lein from exiting
                          (block-thread-forever))
                rsync (core/do-rsync! tmp))))))

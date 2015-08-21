(ns leiningen.static
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [static.cli :as cli]
            [static.config :as config]
            [static.core :as core]
            [static.logging :as logging]))

(defn get-config [project]
  (if (map? (:static-config project))
    (:static-config project)
    (config/load-standalone-config)))

(defn ^:no-project-needed ^:pass-through-help
  static
  "Static: a static website generator.

  use --help to see help."
  [project & args]
  (let [{:keys [options errors]} (cli/parse-args args)
        {show-options :options :keys [build tmp jetty watch rsync help]} options]

    (cond
      errors (println (str/join "\n" errors))
      help (println (str/join "\n" ["Static: a static blog generator."
                                    "Usage: lein static <option>:"
                                    (cli/summarize-opts)]))
      (some identity [build watch jetty rsync])
      (do (logging/setup-logging!)
          (config/init-config! (get-config project))
          (cond build (core/do-build!)
                watch (core/do-watch! tmp)
                jetty (core/do-jetty! tmp)
                rsync (core/do-rsync! tmp))
          ;; Stop lein from exiting
          (loop []
            (Thread/sleep 1000)
            (recur))))))

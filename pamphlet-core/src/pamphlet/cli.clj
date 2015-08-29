(ns pamphlet.cli
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [pamphlet.config :as config]
            [pamphlet.core :as core]
            [pamphlet.io :as io]
            [pamphlet.logging :as logging])
  (:import (java.io File)
           (org.apache.commons.io FileUtils FilenameUtils))
  (:gen-class))

(def cli-opts [[nil "--build" "Build Site."]
               [nil "--tmp" "Use tmp location override :out-dir"]
               [nil "--jetty" "View Site."]
               [nil "--watch" "Watch Site and Rebuild on Change."]
               [nil "--rsync" "Deploy Site."]
               [nil "--help" "Show help"]])

(defn summarize-opts []
  (:summary (cli/parse-opts [] cli-opts)))

(defn parse-args
  "Returns a map of :options (a map of enabled options), :summary (a string),
  and :errors (possibly nil, possibly an error string)."
  [args]
  (cli/parse-opts args cli-opts))

(defn -main [& args]
  (let [{:keys [options errors]} (parse-args args)
        {:keys [build tmp jetty watch rsync help]} options]

    (when errors
      (println (str/join "\n" errors))
      (System/exit 1))

    (when help
      (println (str/join "\n" ["Pamphlet: a static blog generator."
                               "Usage: pamphlet <option>:"
                               (summarize-opts)]))
      (System/exit 0))

    (logging/setup-logging!)

    (config/init-config! (config/load-standalone-config))

    (cond build (core/do-build!)
          watch (core/do-watch! tmp)
          jetty (core/do-jetty! tmp)
          rsync (core/do-rsync! tmp)
          :default (println "Use --help for options."))))

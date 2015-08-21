(ns static.cli
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [static.config :as config]
            [static.core :as core]
            [static.io :as io]
            [static.logging :as logging])
  (:import (java.io File)
           (org.apache.commons.io FileUtils FilenameUtils))
  (:gen-class))

(def cli-opts [[nil "--build" "Build Site."]
               [nil "--tmp" "Use tmp location override :out-dir"]
               [nil "--jetty" "View Site."]
               [nil "--watch" "Watch Site and Rebuild on Change."]
               [nil "--rsync" "Deploy Site."]
               [nil "--help" "Show help"]
               [nil "--options" "Show help"]])
(defn summarize-opts []
  (:summary (cli/parse-opts [] cli-opts)))


(defn -main [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-opts)
        {show-options :options :keys [build tmp jetty watch rsync help]} options]

    (when errors
      (println (str/join "\n" errors))
      (System/exit 1))

    (when help
      (println (str/join "\n" ["Static: a static blog generator."
                               "Usage: static <option>:"
                               (summarize-opts)]))
      (System/exit 0))

    (logging/setup-logging!)

    (config/init-config! (config/load-standalone-config))

    (cond build (core/do-build!)
          watch (core/do-watch! tmp)
          jetty (core/do-jetty! tmp)
          rsync (core/do-rsync! tmp)
          :default (println "Use --help for options."))))

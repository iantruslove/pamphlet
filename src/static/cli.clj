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
               [nil "--help" "Show help"]])

(defn -main [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-opts)
        {:keys [build tmp jetty watch rsync help]} options]

    (when errors
      (println (str/join "\n" errors))
      (System/exit 1))

    (when help
      (println "Static: a static blog generator.\n")
      (println "Usage: static <option>:")
      (println summary)
      (System/exit 0))

    (logging/setup-logging!)

    (cond build (core/do-build!)
          watch (core/do-watch! tmp)
          jetty (core/do-jetty! tmp)
          rsync (core/do-rsync! tmp)
          :default (println "Use --help for options."))))

(ns static.cli
  (:require [clojure.java.browse :as browse]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [static.config :as config]
            [static.core :as core]
            [static.io :as io])
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

    (core/setup-logging!)

    (let [out-dir (:out-dir (config/config))
          tmp-dir (str (System/getProperty "java.io.tmpdir") "/" "static/")]

      (when (or tmp
                (and (:atomic-build (config/config))
                     build))
        (let [loc (FilenameUtils/normalize tmp-dir)]
          (config/set!-config :out-dir loc)
          (log/info (str "Using tmp location: " (:out-dir (config/config))))))

      (cond build (core/log-time-elapsed "Build took "
                                         (core/create))
            watch (do (core/watch-and-rebuild)
                      (future (jetty/run-jetty core/serve-static {:port 8080}))
                      (browse/browse-url "http://127.0.0.1:8080"))
            jetty (do (future (jetty/run-jetty core/serve-static {:port 8080}))
                      (browse/browse-url "http://127.0.0.1:8080"))
            rsync (let [{:keys [rsync out-dir host user deploy-dir]} (config/config)]
                    (io/deploy-rsync rsync out-dir host user deploy-dir))
            :default (println "Use --help for options."))

      (when (and (:atomic-build (config/config))
                 build)
        (FileUtils/deleteDirectory (File. out-dir))
        (FileUtils/moveDirectory (File. tmp-dir) (File. out-dir))))

    (when-not watch
      (shutdown-agents))))

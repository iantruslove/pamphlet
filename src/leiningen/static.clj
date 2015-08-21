(ns leiningen.static
  (:require [clojure.pprint :refer [pprint]]
            [static.cli :as cli]))

(defn ^:no-project-needed ^:pass-through-help
  static
  "Static: a static website generator.

  use --help to see help."
  [project & args]
  (apply cli/-main args)
  ;; Stop lein from exiting
  (loop []
    (Thread/sleep 1000)
    (recur)))

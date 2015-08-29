(ns pamphlet.config
  (:require [clojure.tools.logging :as log])
  (:import (java.io File)))

(def config-defaults
  {:site-title "A Static Blog"
   :site-description "Default blog description"
   :site-url "https://github.com/iantruslove/pamphlet"
   :in-dir "resources/"
   :out-dir "html/"
   :post-out-subdir ""
   :default-template "default.clj"
   :default-extension "html"
   :encoding "UTF-8"
   :posts-per-page 2
   :blog-as-index true
   :create-archives true
   :org-export-command '(progn
                         (org-html-export-as-html nil nil nil t nil)
                         (with-current-buffer "*Org HTML Export*"
                           (princ (org-no-properties (buffer-string)))))})

(defonce current-config (atom nil))

(defn load-standalone-config
  "Loads and parses key/val pairs from config.clj.
  Returns nil if file does not exist."
  []
  (let [config-file (File. "config.clj")]
    (when (.exists config-file)
      (apply hash-map (read-string (slurp config-file))))))

(defn validate-config
  "Returns the config if it is valid, nil otherwise."
  [config]
  (cond
    ;;if emacs key is set make sure executable exists.
    (and (:emacs config) (not (.exists (File. (:emacs config)))))
    (log/error "Path to Emacs not valid.")
    :else config))

(defn init-config!
  "Resets the current system configuration."
  [config]
  (if-let [config (validate-config config)]
    (reset! current-config (merge config-defaults config))
    (do (log/error "No valid config.")
        (System/exit 0))))

(defn set-config! [k v]
  (swap! current-config #(assoc % k v)))

(defn config
  ([] @current-config)
  ([k] (get (config) k)))

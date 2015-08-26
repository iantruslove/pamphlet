(ns static.io
  (:require [clojure.core.memoize :refer [memo]]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cssgen :as css-gen]
            [hiccup.core :as hiccup]
            [static.config :as config]
            [static.io.cache :as cache]
            [stringtemplate-clj.core :as string-template])
  (:import (java.io File)
           (org.apache.commons.io FileUtils FilenameUtils)
           (org.pegdown PegDownProcessor)))

(defn- split-file [content]
  (let [idx (.indexOf content "---" 4)]
    [(.substring content 4 idx) (.substring content (+ 3 idx))]))

(defn- metadata-entries [metadata-line]
  (if-let [matches (re-find #"([^:#\+]+): (.+)$" metadata-line)]
    (let [[_ k v] matches
          metadata-key (keyword (.toLowerCase k))
          metadata-map {metadata-key v}]
      (cond-> metadata-map
        (= metadata-key :tags) (assoc :keyword-tags (map keyword (str/split v #" ")))
        (= metadata-key :keywords) (assoc :keyword-keywords (map keyword (str/split v #" ")))))
    {}))

(defn- prepare-metadata
  "Converts a string of lines of \"<key>: <val>\" into a map of
  {:key val ...}.
  The values for `:tags` and `:keywords` are split into lists of keywords."
  [metadata]
  (->> (str/split-lines metadata)
       (map metadata-entries)
       reverse
       (reduce merge {})))

(defn- read-markdown [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (config/config :encoding)))]
    [(prepare-metadata metadata)
     (delay (.markdownToHtml (PegDownProcessor. org.pegdown.Extensions/TABLES) content))]))

(defn- read-html [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (config/config :encoding)))]
    [(prepare-metadata metadata) (delay content)]))

(defn read-org* [file]
  (if (not (config/config :emacs))
    (do (log/error "Path to Emacs is required for org files.")
        (System/exit 0)))
  (let [{:keys [encoding emacs emacs-eval org-export-command emacs-config]} (config/config)
        metadata (prepare-metadata
                  (apply str (take 500 (slurp file :encoding encoding))))
        content (delay (:out (sh/sh emacs
                                    "-batch" "-eval"
                                    (str
                                     "(progn "
                                     (apply str (map second emacs-eval))
                                     " (find-file \"" (.getAbsolutePath file) "\") "
                                     org-export-command ")"))))]
    [metadata content]))

;; We really need to parse each file once. So we memoize the results
(defn read-org [file]
  ;; BUG: not invalidating the file cache prevents `--watch` from picking up
  ;; changes to org files. Need to get a bit more clever, and either
  ;; use some kind of cache busting based on the file watcher, or
  ;; parse the org files quickly (e.g. without emacs).
  (cache/read-cached-file [:org file] read-org*))

(defn- read-clj [file]
  (let [[metadata & content] (read-string
                              (str \( (slurp file :encoding (config/config :encoding)) \)))]
    [metadata (delay (binding [*ns* (the-ns 'static.core)]
                       (->> content
                            (map eval)
                            last
                            hiccup/html)))]))

(defn- read-cssgen [file]
  (let [metadata {:extension "css" :template :none}
        content (read-string
                 (slurp file :encoding (config/config :encoding)))
        to-css  #(str/join "\n" (doall (map css-gen/css %)))]
    [metadata (delay (binding [*ns* (the-ns 'static.core)] (-> content eval to-css)))]))

(defn read-doc [^File f]
  (let [extension (FilenameUtils/getExtension (str f))]
    (cond (= extension "markdown") (read-markdown f)
          (= extension "md") (read-markdown f)
          (= extension "org") (read-org f)
          (= extension "html") (read-html f)
          (= extension "clj") (read-clj f)
          (= extension "cssgen") (read-cssgen f)
          :default (throw (Exception. "Unknown Extension.")))))

(defn dir-path [dir]
  (let [in-dir (config/config :in-dir)]
    (cond (= dir :templates) (str in-dir "templates/")
          (= dir :public) (str in-dir "public/")
          (= dir :site) (str in-dir "site/")
          (= dir :posts) (str in-dir "posts/")
          :default (throw (Exception. "Unknown Directory.")))))

(defn list-files [d]
  (let [d (File. (dir-path d))]
    (if (.isDirectory d)
      (sort
       (FileUtils/listFiles d (into-array ["markdown"
                                           "md"
                                           "clj"
                                           "cssgen"
                                           "org"
                                           "html"]) true)) [] )))

(defn template-file [template-name]
  (let [full-path (str (dir-path :templates) template-name)
        file (File. full-path)]
    (when (not (.exists file))
      (log/warn "Template does not exist: " full-path))
    file))

(defn template-language [^File template]
  (-> template .getAbsolutePath FilenameUtils/getExtension .toLowerCase))

(defmulti read-template* template-language)

(defmethod read-template* "clj" [^File template]
  [:clj (-> (str \((slurp template :encoding (config/config :encoding)) \))
            read-string)])

(defmethod read-template* :default [^File template]
  [:html (string-template/load-template (.getParent template)
                                        (.getName template))])

(defn read-template [^File template-file]
  (cache/read-cached-file [:template template-file] read-template*))

(defn write-out-dir [file str]
  (let [{:keys [out-dir encoding]} (config/config)]
    (FileUtils/writeStringToFile (File. out-dir file) str encoding)))

(defn deploy-rsync [rsync out-dir host user deploy-dir]
  (let [cmd [rsync "-avz" "--delete" "--checksum" "-e" "ssh"
             out-dir (str user "@" host ":" deploy-dir)]]
    (log/info (:out (apply sh/sh cmd)))))

(ns static.io
  (:require [clojure.core.memoize :refer [memo]]
            [clojure.java.shell :as sh]
            [clojure.tools.logging :as log]
            [cssgen :as css-gen]
            [hiccup.core :as hiccup]
            [static.config :as config]
            [stringtemplate-clj.core :as string-template])
  (:import (java.io File)
           (org.apache.commons.io FileUtils FilenameUtils)
           (org.pegdown PegDownProcessor)))

(defn- split-file [content]
  (let [idx (.indexOf content "---" 4)]
    [(.substring content 4 idx) (.substring content (+ 3 idx))]))

(defn- prepare-metadata [metadata]
  (reduce (fn [h [_ k v]]
            (let [key (keyword (.toLowerCase k))]
              (if (not (h key))
                (assoc h key v)
                h)))
          {} (re-seq #"([^:#\+]+): (.+)(\n|$)" metadata)))

(defn- read-markdown [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (config/config :encoding)))]
    [(prepare-metadata metadata)
     (delay (.markdownToHtml (PegDownProcessor. org.pegdown.Extensions/TABLES) content))]))

(defn- read-html [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (config/config :encoding)))]
    [(prepare-metadata metadata) (delay content)]))

(defn read-org [file]
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
        to-css  #(clojure.string/join "\n" (doall (map css-gen/css %)))]
    [metadata (delay (binding [*ns* (the-ns 'static.core)] (-> content eval to-css)))]))

(defn read-doc [f]
  (let [extension (FilenameUtils/getExtension (str f))]
    (cond (or (= extension "markdown") (= extension "md"))
          (read-markdown f)
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

(def read-template
  (memo
   (fn [template]
     (let [extension (FilenameUtils/getExtension (str template))]
       (cond (= extension "clj")
             [:clj
              (-> (str (dir-path :templates) template)
                  (File.)
                  (#(str \(
                         (slurp % :encoding (config/config :encoding))
                         \)))
                  read-string)]
             :default
             [:html
              (string-template/load-template (dir-path :templates) template)])))))

(defn write-out-dir [file str]
  (let [{:keys [out-dir encoding]} (config/config)]
    (FileUtils/writeStringToFile (File. out-dir file) str encoding)))

(defn deploy-rsync [rsync out-dir host user deploy-dir]
  (let [cmd [rsync "-avz" "--delete" "--checksum" "-e" "ssh"
             out-dir (str user "@" host ":" deploy-dir)]]
    (log/info (:out (apply sh/sh cmd)))))

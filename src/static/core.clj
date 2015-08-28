(ns static.core
  (:require [clojure.core.memoize :refer [memo-clear!]]
            [clojure.java.browse :as browse]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup.core :as hiccup]
            [hiccup.page :refer :all]
            [hiccup.util :refer :all]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :refer :all]
            [static.config :as config]
            [static.io :as io]
            [static.logging :as logging]
            [stringtemplate-clj.core :as string-template]
            [watchtower.core :as watcher])
  (:import (java.io File)
           (java.net URL)
           (java.text SimpleDateFormat)
           (org.apache.commons.io FileUtils FilenameUtils)))


(defn parse-date
  "Format date from in spec to out spec."
  [in out date]
  (.format (SimpleDateFormat. out) (.parse (SimpleDateFormat. in) date)))

(defn post-url
  "Given a post file return its URL."
  [file]
  (let [name (FilenameUtils/getBaseName (str file))
        url (str (apply str (interleave (repeat \/) (.split name "-" 4))) "/")]
    (if (empty? (:post-out-subdir (config/config)))
      url
      (str "/" (:post-out-subdir (config/config)) url))))

(defn site-url [f & [ext]]
  (-> (str f)
      (.replaceAll "\\\\" "/")
      (.replaceAll (io/dir-path :site) "")
      (FilenameUtils/removeExtension)
      (str "."
           (or ext
               (config/config :default-extension)))))

(def ^:dynamic metadata nil)
(def ^:dynamic content nil)

(defn template [page]
  (let [[m c] page
        template (if (:template m)
                   (:template m)
                   (config/config :default-template))
        [type template-string] (if (= template :none)
                                 [:none c]
                                 (io/read-template template))]
    (cond (or (= type :clj)
              (= type :none))
          (binding [*ns* (the-ns 'static.core)
                    metadata m content c]
            (hiccup/html (map #(eval %) template-string)))
          (= type :html)
          (let [m (->> m
                       (reduce (fn[h [k v]]
                                 (assoc h (name k) v)) {}))]
            (string-template/render-template template-string
                                             (merge m {"content" c}))))))

(defn process-site
  "Process site pages."
  []
  (dorun
   (pmap
    #(let [f %
           [metadata content] (io/read-doc f)]

       (if (empty? @content)
         (log/warn (str "Empty Content: " f)))

       (io/write-out-dir
        (site-url f (:extension metadata))
        (template [(assoc metadata :type :site) @content])))
    (io/list-files :site))))

;;
;; Create RSS Feed.
;;

(defn post-xml
  "Create RSS item node."
  [file]
  (let [[metadata content] (io/read-doc file)]
    [:item
     [:title (escape-html (:title metadata))]
     [:link  (str (URL. (URL. (config/config :site-url)) (post-url file)))]
     [:pubDate (parse-date "yyyy-MM-dd" "E, d MMM yyyy HH:mm:ss Z"
                           (re-find #"\d*-\d*-\d*"
                                    (FilenameUtils/getBaseName (str file))))]
     [:description (escape-html @content)]]))

(defn create-rss
  "Create RSS feed."
  []
  (let [{:keys [site-title site-url site-description]} (config/config)
        in-dir (File. (io/dir-path :posts))
        posts (take 10 (reverse (io/list-files :posts)))]
    (io/write-out-dir "rss-feed"
                   (hiccup/html (xml-declaration "UTF-8")
                         (doctype :xhtml-strict)
                         [:rss {:version "2.0"}
                          [:channel
                           [:title (escape-html site-title)]
                           [:link site-url]
                           [:description
                            (escape-html site-description)]
                           (pmap post-xml posts)]]))))

(defn create-sitemap
  "Create sitemap."
  []
  (io/write-out-dir
   "sitemap.xml"
   (let [base (config/config :site-url)]
     (hiccup/html (xml-declaration "UTF-8")
                  [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
                   [:url [:loc base]]
                   (map #(vector :url [:loc (str base %)])
                        (map post-url (io/list-files :posts)))
                   (map #(vector :url [:loc (str base "/" %)])
                        (map site-url (io/list-files :site)))]))))

;;
;; Create Tags Page.
;;

(defn tag-map
  "Create a map of tags and posts contining them. {tag1 => [url1 url2..]}"
  []
  (reduce
   (fn [h v]
     (let [[metadata] (io/read-doc v)
           info [(post-url v) (:title metadata)]
           tags (.split (:tags metadata) " ")]
       (reduce
        (fn [m p]
          (let [[tag info] p]
            (if (nil? (m tag))
              (assoc m tag [info])
              (assoc m tag (conj (m tag) info)))))
        h (partition 2 (interleave tags (repeat info))))))
   (sorted-map)
   (filter #(not (nil? (:tags (first (io/read-doc %))))) (io/list-files :posts))))

(defn create-tags
  "Create and write tags page."
  []
  (io/write-out-dir "tags/index.html"
                    (template
                     [{:title "Tags"
                       :template (config/config :default-template)}
                      (hiccup/html
                       [:h2 "Tags"]
                       (map (fn[t]
                              (let [[tag posts] t]
                                [:h4 [:a {:name tag} tag]
                                 [:ul
                                  (map #(let [[url title] %]
                                          [:li [:a {:href url} title]])
                                       posts)]]))
                            (tag-map)))])))

;;
;; Create pages for latest posts.
;;

(defn pager
  "Return previous, next navigation links."
  [page max-index posts-per-page]
  (let [count-total (count (io/list-files :posts))
        older [:div {:class "pager-left"}
               [:a {:href (str "/latest-posts/" (- page 1) "/")}
                "&laquo; Older Entries"]]
        newer [:div {:class "pager-right"}
               [:a {:href (str "/latest-posts/" (+ page 1) "/")}
                "Newer Entries &raquo;"]]]
    (cond
     (<= count-total posts-per-page) nil
     (= page max-index) (list older)
     (= page 0) (list newer)
     :default (list older newer))))

(defn snippet
  "Render a post for display in index pages."
  [f]
  (let [[metadata content] (io/read-doc f)]
    [:div [:h2 [:a {:href (post-url f)} (:title metadata)]]
     [:p {:class "publish_date"}
      (parse-date "yyyy-MM-dd" "dd MMM yyyy"
                  (re-find #"\d*-\d*-\d*"
                           (FilenameUtils/getBaseName (str f))))]
     [:p @content]]))

(defn create-latest-posts
  "Create and write latest post pages."
  []
  (let [{:keys [posts-per-page
                site-title-page
                site-title
                site-description
                default-template]} (config/config)
        posts (partition posts-per-page
                         posts-per-page
                         []
                         (reverse (io/list-files :posts)))
        pages (partition 2 (interleave (reverse posts) (range)))
        [_ max-index] (last pages)]
    (doseq [[posts page] pages]
      (io/write-out-dir
       (str "latest-posts/" page "/index.html")
       (template
        [{:title (config/config :site-title)
          :description (config/config :site-description)
          :template (config/config :default-template)}
         (hiccup/html (list (map #(snippet %) posts)
                            (pager page max-index posts-per-page)))])))))

;;
;; Create Archive Pages.
;;

(defn post-count-by-mount
  "Create a map of month to post count {month => count}"
  []
  (->> (io/list-files :posts)
       (reduce (fn [h v]
                 (let  [date (re-find #"\d*-\d*"
                                      (FilenameUtils/getBaseName (str v)))]
                   (if (nil? (h date))
                     (assoc h date 1)
                     (assoc h date (+ 1 (h date)))))) {})
       (sort-by first)
       reverse))

(defn create-archives
  "Create and write archive pages."
  []
  ;;create main archive page.
  (io/write-out-dir
   (str "archives/index.html")
   (template
    [{:title "Archives" :template (config/config :default-template)}
     (hiccup/html
      (list [:h2 "Archives"]
            [:ul
             (map
              (fn [[mount count]]
                [:li [:a
                      {:href (str "/archives/" (.replace mount "-" "/") "/")}
                      (parse-date "yyyy-MM" "MMMM yyyy" mount)]
                 (str " (" count ")")])
              (post-count-by-mount))]))]))

  ;;create a page for each month.
  (dorun
   (pmap
    (fn [month]
      (let [posts (->> (io/list-files :posts)
                       (filter #(.startsWith
                                 (FilenameUtils/getBaseName (str %)) month))
                       reverse)]
        (io/write-out-dir
         (str "archives/" (.replace month "-" "/") "/index.html")
         (template
          [{:title "Archives" :template (:default-template (config/config))}
           (hiccup/html (map snippet posts))]))))
    (keys (post-count-by-mount)))))

(defn create-aliases
  "Create redirect pages."
  ([]
   (doseq [post (io/list-files :posts)]
     (create-aliases post))
   (doseq [site (io/list-files :site)]
     (create-aliases site)))
  ([file]
   (let [doc (io/read-doc file)]
     (when-let [aliases (-> doc first :alias)]
       (doseq [alias (read-string aliases)]
         (io/write-out-dir
          alias
          (hiccup/html [:html
                        [:head
                         [:meta {:http-equiv "content-type" :content "text/html; charset=utf-8"}]
                         [:meta {:http-equiv "refresh" :content (str "0;url=" (post-url file))}]]])))))))

(defn process-posts
  "Create and write post pages."
  []
  (dorun
   (pmap
    #(let [f %
           [metadata content] (io/read-doc f)
           out-file (reduce (fn [h v] (.replaceFirst h "-" "/"))
                            (FilenameUtils/getBaseName (str f)) (range 3))
           out-file (if (empty? (:post-out-subdir (config/config)))
                      out-file
                      (str (:post-out-subdir (config/config)) "/" out-file))]

       (when (empty? @content)
         (log/warn (str "Empty Content: " f)))

       (io/write-out-dir
        (str out-file "/index.html")
        (template
         [(assoc metadata :type :post :url (post-url f)) @content])))
    (io/list-files :posts))))

(defn process-public
  "Copy public from in-dir to out-dir."
  []
  (let [in-dir (File. (io/dir-path :public))
        out-dir (File. (config/config :out-dir))]
    (doseq [f (map #(File. in-dir %) (.list in-dir))]
      (if (.isFile f)
        (FileUtils/copyFileToDirectory f out-dir)
        (FileUtils/copyDirectoryToDirectory f out-dir)))))

(defn create
  "Build Site."
  []
  (let [{create-archives? :create-archives
         blog-as-index? :blog-as-index
         :keys [out-dir]} (config/config)]
    (doto (File. out-dir)
      (FileUtils/deleteDirectory)
      (.mkdir))

    (logging/log-time-elapsed "Processing Public " (process-public))
    (logging/log-time-elapsed "Processing Site " (process-site))

    (when (pos? (-> (io/dir-path :posts) (File.) .list count))
      (logging/log-time-elapsed "Processing Posts " (process-posts))
      (logging/log-time-elapsed "Creating RSS " (create-rss))
      (logging/log-time-elapsed "Creating Tags " (create-tags))

      (when create-archives?
        (logging/log-time-elapsed "Creating Archives " (create-archives)))

      (logging/log-time-elapsed "Creating Sitemap " (create-sitemap))
      (logging/log-time-elapsed "Creating Aliases " (create-aliases))

      (when blog-as-index?
        (logging/log-time-elapsed "Creating Latest Posts " (create-latest-posts))
        (let [max (apply max (map read-string (-> out-dir
                                                  (str "latest-posts/")
                                                  (File.)
                                                  .list)))]
          (FileUtils/copyFile
           (File. (str out-dir "latest-posts/" max "/index.html"))
           (File. (str out-dir "index.html"))))))))

(defn serve-static [req]
  (let [mime-types {".clj" "text/plain"
                    ".mp4" "video/mp4"
                    ".ogv" "video/ogg"}]
    (if-let [f (file-response (:uri req) {:root (config/config :out-dir)})]
      (if-let [mimetype (mime-types (re-find #"\..+$" (:uri req)))]
        (merge f {:headers {"Content-Type" mimetype}})
        f))))

(defn watch-and-rebuild
  "Watch for changes and rebuild site on change."
  []
  (watcher/watcher [(config/config :in-dir)]
                   (watcher/rate 1000)
                   (watcher/on-change
                    (fn [_]
                      (log/info "Rebuilding site...")
                      (try
                        (memo-clear! io/read-template)
                        (create)
                        (catch Exception e
                          (log/error (str "Exception thrown while building site! " e))))))))

(defn new-tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/" "static/"))

(defn do-watch! [use-system-tmp-dir?]
  (when use-system-tmp-dir?
    (config/set-config! :out-dir (FilenameUtils/normalize (new-tmp-dir)))
    (log/info (str "Using tmp location: " (config/config :out-dir))))
  (watch-and-rebuild)
  (future (jetty/run-jetty serve-static {:port 8080}))
  (browse/browse-url "http://127.0.0.1:8080"))

(defn do-build! []
  (let [tmp-dir (new-tmp-dir)
        atomic-build? (config/config :atomic-build)]
    (when atomic-build?
      (config/set-config! :out-dir (FilenameUtils/normalize tmp-dir))
      (log/info (str "Using tmp location: " (config/config :out-dir))))
    (logging/log-time-elapsed "Build took "
                              (create))
    (when atomic-build?
      (let [out-dir (config/config :out-dir)]
        (FileUtils/deleteDirectory (File. out-dir))
        (FileUtils/moveDirectory (File. tmp-dir) (File. out-dir))))
    (shutdown-agents)))

(defn do-jetty! [use-system-tmp-dir?]
  (when use-system-tmp-dir?
    (config/set-config! :out-dir (FilenameUtils/normalize (new-tmp-dir)))
    (log/info (str "Using tmp location: " (config/config :out-dir))))
  (future (jetty/run-jetty serve-static {:port 8080}))
  (browse/browse-url "http://127.0.0.1:8080")
  (shutdown-agents))

(defn do-rsync! [use-system-tmp-dir?]
  (when use-system-tmp-dir?
    (config/set-config! :out-dir (FilenameUtils/normalize (new-tmp-dir)))
    (log/info (str "Using tmp location: " (config/config :out-dir))))
  (let [{:keys [rsync out-dir host user deploy-dir]} (config/config)]
    (io/deploy-rsync rsync out-dir host user deploy-dir))
  (shutdown-agents))

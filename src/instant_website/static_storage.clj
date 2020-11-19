(ns instant-website.static-storage
  (:require
    [clojure.tools.logging :refer [log]]
    [clojure.java.io :refer [make-parents copy file]]
    [clojure.string :as string]
    ;;
    [hiccup.core :refer [html]]
    [clj-http.client :as http]
    ;;
    [instant-website.config :refer [config]]
    [instant-website.figcup :refer [create-google-fonts-url]]))

;; Namespace for functions that'll put websites on disk, so we can use something
;; like caddy to serve it instead of application code

;; Not proud of the spaghetti paths nor the liberal use of spit, needs to change

(def chrome-useragent
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.75 Safari/537.36")

(def fonts-gstatic-regex #"(https\:\/\/fonts\.gstatic\.com.*\.woff2)")

(defn http-get [url as]
  (:body (http/get url
                   ;; webfonts API gives different formats depending on user-agent
                   ;; so here we specify Chrome so we get woff2 instead of ttf
                   ;; fonts
                   {:as as
                    :headers {"User-Agent" chrome-useragent}})))
(def http-get-memo (memoize http-get))

(defn copy-url-to-disk [url filepath]
  (copy
    (http-get-memo url :byte-array)
    (file filepath)))

(def copy-url-to-disk-memo (memoize copy-url-to-disk))

(defn save-fonts-to-disk! [website-id website-version page-path webfonts-stylesheet-url]
  (let [directory (format "%s/%s/%s/"
                          (:websites-storage-path config)
                          website-id
                          website-version)
        local-stylesheet-path (str directory
                                   page-path
                                   "/fonts.css")
        _ (println "getting stylesheet")
        stylesheet-doc (http-get-memo webfonts-stylesheet-url "UTF-8")
        to-replace (atom [])]
    (doseq [[font-url] (re-seq fonts-gstatic-regex stylesheet-doc)]
      (let [filename (.getName (file font-url))
            full-path (str directory "_fonts/" filename)]
        (make-parents full-path)
        (copy-url-to-disk-memo font-url full-path)
        (swap! to-replace conj [font-url (str "../_fonts/" filename)])))
    (make-parents local-stylesheet-path)
    (spit
      local-stylesheet-path
      (reduce (fn [acc [original new]]
                (string/replace acc original new))
              stylesheet-doc
              @to-replace))))

(defn path-for-page [website-id website-version page-path]
  (format "%s/%s/%s/%s/index.html"
          (:websites-storage-path config)
          website-id
          website-version
          page-path))

(defn redirect-hiccup [to]
  [:html
   {:lang "en"
    :style {:overflow-y "scroll"}} ;; TODO make this configurable
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "refresh"
            :content (str "0; URL='"
                          to
                          "'")}]
    [:title "InstantWebsite.app"]]
   [:body
    "Redirecting..."]])

;; <meta http-equiv="refresh" content="0; URL='http://new-website.com'" />
(defn redirect-to-startpage [to]
  (html {:mode :html
         :escape-strings? false}
        (redirect-hiccup to)))

(defn save-website-to-disk! [website-id startpage]
  (let [path-on-disk (format "%s/%s/index.html"
                             (:websites-storage-path config)
                             website-id)]
    (log :info (str "Saving website to " path-on-disk))
    (make-parents path-on-disk)
    (spit path-on-disk (redirect-to-startpage startpage))))

(defn save-pages-to-disk! [website website-version]
  (let [website-id (:crux.db/id website)
        root-redirect (format "%s/%s/index.html"
                              (:websites-storage-path config)
                              website-id)
        startpage-redirect (format "%s/%s/%s/index.html"
                                   (:websites-storage-path config)
                                   website-id
                                   website-version)]
    (doseq [[path page] (:website/pages website)]
      (let [page-path-on-disk (path-for-page website-id website-version (:page/path page))]
        (log :info (str "Saving page to " page-path-on-disk))
        (log :info "Saving page")
        (make-parents page-path-on-disk)
        (spit page-path-on-disk (:page/html-content page))
        (log :info "Saving fonts")
        (log :info (:page/fonts page))
        (when-not (empty? (:page/fonts page))
          (save-fonts-to-disk! website-id
                               website-version
                               (:page/path page)
                               (create-google-fonts-url (:page/fonts page))))
        (log :info "Saving root redirect")
        (make-parents root-redirect)
        (spit root-redirect (redirect-to-startpage (str website-version
                                                        "/"
                                                        (:website/startpage website))))
        (log :info "Saving startpage redirect")))
    (make-parents startpage-redirect)
    (spit startpage-redirect (redirect-to-startpage (:website/startpage website)))))

(defn save-image-to-disk! [website website-rev image-hash image-bytes]
  (let [path-on-disk (format "%s/%s/%s/_images/%s.png"
                             (:websites-storage-path config)
                             (:crux.db/id website)
                             website-rev
                             image-hash)]
    (log :info (str "Saving image to " path-on-disk))
    (make-parents path-on-disk)
    (copy image-bytes (file path-on-disk))))

(defn save-vector-to-disk! [website website-rev vector-id vector-bytes]
  (let [path-on-disk (format "%s/%s/%s/_vectors/%s.svg"
                             (:websites-storage-path config)
                             (:crux.db/id website)
                             website-rev
                             vector-id)]
    (log :info (str "Saving vector to " path-on-disk))
    (make-parents path-on-disk)
    (copy vector-bytes (file path-on-disk))))

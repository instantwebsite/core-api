(ns instant-website.handlers.pages
  (:require
    [clojure.tools.logging :refer [log]]
    ;;
    [hiccup.core :refer [html]]
    [cheshire.core :refer [parse-string parse-stream generate-string]]
    [crux.api :as crux]
    [iapetos.collector.fn :as fn]
    ;;
    [instant-website.db :as db]
    [instant-website.metrics :refer [registry]]
    [instant-website.figcup :as figcup]
    [instant-website.static-storage :refer [save-pages-to-disk!
                                            save-fonts-to-disk!]]))

(def banner
  [:div
   {:style {:position "fixed"
            :bottom  "0px"
            :left  "0px"
            :background-color "white"
            :padding "10px"
            :font-size  "11px"
            :border-top-right-radius "2px"
            :font-family  "Arial,sans-serif"
            :letter-spacing  "1px"}}
   "Created with "
   [:a
     {:href "https://instantwebsite.app"
      :style {:color "#1a718c"}
      :target "_blank"}
     "Instant Website"]])

(defn wrap-hiccup [website elements font-url is-user-pro]
  [:html
   {:lang "en"
    :style {:overflow-y "scroll"}} ;; TODO make this configurable
   [:head
    [:meta {:charset "utf-8"}]
    ;; TODO should come from config
    [:title "InstantWebsite.app"]
    [:style "html, body {margin:0;padding:0;}"]
    (when font-url
      [:link
       {:href font-url
        :rel "stylesheet"}])]
   [:body
    elements
    (when-not is-user-pro
      banner)]])

(defn read-body [req]
  (if (:dev-body req)
    (:dev-body req)
    (if (:body req)
      (-> req :body .bytes slurp)
      nil)))

(defn create-page-from-json-str [website json-str is-user-pro]
  (let [json (parse-string json-str true)
        hiccup-res (figcup/figma->hiccup json)
        metadata (meta hiccup-res)
        page-name (-> hiccup-res second :name)
        fonts @(:fonts metadata)
        wrapped-hiccup (wrap-hiccup website hiccup-res "fonts.css" is-user-pro)
        html-res (html {:mode :html
                        :escape-strings? false}
                       wrapped-hiccup)]
    (db/->page page-name page-name json-str html-res fonts)))

;; The code below is old code, as handle-create is only called from an old
;; version of the plugin. As the plugin is in review right now, and I'm
;; not sure which version they'll end up reviewing, I'm leaving this code
;; active now and once we get full plugin update flow working, we can remove
;; this

(defn create-page-from-request [req website]
  (let [body (read-body req)]
    (create-page-from-json-str website body)))

(defn handle-create [req]
  (let [crux-node (:crux req)
        website-id (-> req :route-params :website-id)
        user-id (-> req :identity :user-id)
        website (db/website-id->website crux-node website-id)]

    (if (nil? website)
      {:status 404}
      (let [page (create-page-from-request req website)

            _ (log :info (str "creating page with name " (:page/title page)))
            new-website (update website
                                :website/pages
                                assoc
                                (:page/path page)
                                page)]
        (crux/await-tx
          crux-node
          (db/put! crux-node new-website))
        (log :info "Saving page to disk")
        (save-pages-to-disk! new-website (db/latest-content-hash
                                          crux-node
                                          website-id))
        (generate-string new-website)))))

(fn/instrument! registry #'handle-create)

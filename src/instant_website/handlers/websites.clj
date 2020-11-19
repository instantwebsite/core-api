(ns instant-website.handlers.websites
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :refer [replace]]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    ;;
    [crux.api :as crux]
    [cheshire.core :refer [parse-string generate-string]]
    [rocks.clj.z.core :refer [compress]]
    [ring.util.io :as ring-io]
    [ring.util.response :as response]
    [iapetos.collector.fn :as fn]
    ;;
    [instant-website.auth :as auth]
    [instant-website.db :as db]
    [instant-website.config :refer [config]]
    [instant-website.figcup :refer [clean-component-name]]
    [instant-website.static-storage :refer [save-website-to-disk!
                                            save-pages-to-disk!]]
    [instant-website.metrics :refer [registry]]
    [instant-website.handlers.pages :as pages])
  (:import [java.time Instant Duration]))

(defn handle-index [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)]
    {:status 200
     :body (db/user-id->websites crux-node user-id)}))

(fn/instrument! registry #'handle-index)

(defn read-body [req]
  ;; TODO figure out something better here
  ;; need a BytesInputStream but don't want to convert stuff back<>forward
  ;; just to work with this. Tests pass :dev-body instead of :body that just
  ;; get read straight in
  (if (:dev-body req)
    (:dev-body req)
    (if (:body req)
      (parse-string (-> req :body .bytes slurp) true)
      {})))

(defn read-edn-body [req]
  ;; TODO figure out something better here
  ;; need a BytesInputStream but don't want to convert stuff back<>forward
  ;; just to work with this. Tests pass :dev-body instead of :body that just
  ;; get read straight in
  (if (:dev-body req)
    (:dev-body req)
    (if (:body req)
      (edn/read-string (-> req :body .bytes slurp))
      {})))

(defn handle-create [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)

        received-website (read-body req)

        user (db/user-id->user crux-node user-id)


        website (db/mut (db/->website user)
                        {:website/name (or (:name received-website)
                                           "Placeholder Name")
                         :website/user-id user-id
                         :website/startpage
                         (clean-component-name
                           (or
                             (:startpage received-website)
                             ""))})]
    (save-website-to-disk! (:crux.db/id website)
                           (:website/startpage website))
    (crux/await-tx
      crux-node
      (db/put! crux-node website))
    {:status 201
     :body website}))

(fn/instrument! registry #'handle-create)

(defn handle-update [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        website-id (-> req :route-params :website-id)]
    (auth/check-website-ownership req
      (fn [current-website]
        (let [received-website (read-edn-body req)
              website (db/mut current-website
                              {:website/name (or (:website/name received-website)
                                                 (:website/name current-website))
                               :website/description (or (:website/description received-website)
                                                        (:website/description current-website))
                               :website/startpage
                               (or (clean-component-name
                                     (:website/startpage received-website))
                                   (:website/startpage current-website))})]
          (crux/await-tx
            crux-node
            (db/put! crux-node website))
          ;; TODO handle change of start-page and 
          (save-pages-to-disk! website (db/latest-content-hash crux-node website-id))
          website)))))

(fn/instrument! registry #'handle-update)

(defn regenerate-html-for-pages [website])

;; Same as handle-create, but also adds pages, all in one request
(defn handle-create-with-pages [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        website-id (-> req :route-params :website-id)]
    (println user-id)
    (println website-id)
    (auth/check-website-ownership
      req
      (fn [current-website]
        (let [received-website (read-body req)

              user (db/user-id->user crux-node user-id)

              is-user-pro (= (:user/plan user) :pro)

              pages (reduce (fn [acc curr]
                              (let [page (pages/create-page-from-json-str current-website curr is-user-pro)]
                                (assoc acc (:page/path page) page)))
                            {}
                            (:pages received-website))

              website (db/mut (db/website-id->website crux-node website-id)
                              {:website/name
                               (or (:name received-website)
                                   "Placeholder Name")

                               :website/startpage
                               (clean-component-name
                                 (:startpage received-website))

                               :website/pages
                               pages})]
          (crux/await-tx
            crux-node
            (db/put! crux-node website))
          ;; Save every page here, in one go
          (save-pages-to-disk! website (db/latest-content-hash crux-node website-id))
          {:status 200
           :body {:crux.db/id (:crux.db/id website)}})))))

(fn/instrument! registry #'handle-create-with-pages)

(defn handle-show [req]
  (auth/check-website-ownership
    req
    (fn [current-website]
      {:status 200
       :body current-website})))

(fn/instrument! registry #'handle-show)

(defn remove-dups [k v]
  (reduce (fn [acc curr]
            (println curr)
            (if (some #(= (k curr) (k %)) acc)
              acc
              (conj acc curr)))
          []
          v))

(defn handle-delete [req]
  (auth/check-website-ownership
    req
    (fn [_]
      (let [crux-node (:crux req)
            user-id (-> req :identity :user)
            website-id (-> req :route-params :website-id)]
        (crux/await-tx
          crux-node
          (db/delete! crux-node website-id))))))

(fn/instrument! registry #'handle-delete)

(defn handle-history [req]
  (auth/check-website-ownership
    req
    (fn [_]
      (let [website-id (-> req :route-params :website-id)
            crux-node (:crux req)]
        {:status 200
         :body (remove-dups
                 :crux.db/content-hash
                 (crux/entity-history (crux/db crux-node)
                                      website-id
                                      :desc
                                      {:with-docs? false}))}))))

(fn/instrument! registry #'handle-history)

(defn handle-zip-export [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user :crux.db/id)
        website-id (-> req :route-params :website-id)
        website-version (-> req :route-params :website-version)
        entity (db/website-id->website crux-node website-id)]
    (println (format "Tried loading website from user %s and am user %s"
               (:website/user-id entity)
               user-id))
    (if (= (:website/user-id entity) user-id)
      (let [directory (str (:websites-storage-path config)
                           "/"
                           website-id
                           "/"
                           website-version)
            to-strip (str (:websites-storage-path config)
                          "/"
                          website-id
                          "/")
            zip-file (io/file (str directory ".zip"))]
        (response/response
          (ring-io/piped-input-stream
            (fn [b]
              (->> (io/file directory)
                   file-seq
                   (filter #(.isFile %))
                   (map #(.getPath %))
                   (reduce (fn [acc curr]
                             (assoc acc (replace curr to-strip "") curr))
                           {})
                   (compress b :entries))))))
      {:status 403
       :body "Forbidden"})))

(fn/instrument! registry #'handle-zip-export)

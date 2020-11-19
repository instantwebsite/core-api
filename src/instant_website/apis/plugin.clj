(ns instant-website.apis.plugin
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :refer [log]]
    [clojure.string :refer [replace lower-case]]
    ;;
    [compojure.core :refer [defroutes
                            context
                            GET POST PUT]]
    [iapetos.collector.fn :as fn]
    [buddy.auth.middleware :refer [wrap-authentication]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [cheshire.core :refer [generate-string]]
    [crux.api :as crux]
    ;;
    [instant-website.auth :as auth]
    [instant-website.db :as db]
    [instant-website.metrics :as metrics]
    [instant-website.config :refer [config]]
    [instant-website.handlers.websites :as websites]
    [instant-website.handlers.pages :as pages]
    [instant-website.static-storage :refer [save-image-to-disk!
                                            save-vector-to-disk!]]))

(defn clean-page-name [s]
  (-> s
      (replace " " "-")
      (lower-case)))

(defn handle-create-image [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        user (db/user-id->user crux-node user-id)
        hash (-> req :route-params :hash)
        website-id (-> req :route-params :website-id)
        website-version (db/latest-content-hash crux-node website-id)
        website (db/website-id->website crux-node website-id)
        page-name (clean-page-name (-> req :route-params :page-name))
        image (-> req :params (get "image"))
        image-bytes (io/file (:tempfile image))
        entity (db/->image user hash "")]
    (save-image-to-disk! website website-version hash image-bytes)
    (db/put! crux-node entity)
    (log :debug (str "created image " (:crux.db/id entity)))
    (generate-string entity)))

(fn/instrument! metrics/registry #'handle-create-image)
(defn handle-create-vector [req]
  (let [vector-id (-> req :route-params :*)
        website-id (-> req :route-params :website-id)
        page-name (-> req :route-params :page-name clean-page-name)
        vector (-> req :params (get "vector"))
        vector-bytes (io/file (:tempfile vector))
        vector-bytes-location (str (:storage-path config) "vector-" vector-id)
        _ (io/copy vector-bytes (io/file vector-bytes-location))

        crux-node (:crux req)
        user-id (-> req :identity :user-id)
        user (db/user-id->user crux-node user-id)
        website (db/website-id->website crux-node website-id)
        website-version (db/latest-content-hash crux-node website-id)
        page (db/website-id+page-name->page crux-node
                                            website-id
                                            page-name)

        entity (db/->vector user
                            website
                            page
                            vector-bytes-location
                            vector-id)]
    (save-vector-to-disk! website website-version vector-id vector-bytes)
    (crux/await-tx
      crux-node
      (db/put! crux-node entity))
    (log :debug (str "created vector " (:crux.db/id entity)))
    (generate-string entity)))

(fn/instrument! metrics/registry #'handle-create-vector)

;; These routes are for the plugin
;; - Uses JSON for request/response bodies
;; - Uses API Auth Backend
(defroutes plugin-routes
  (context "/plugin-api"
           []
           (GET "/me" req (auth/auth-required auth/plugin-me-handler))
           (POST "/websites" req (auth/auth-required websites/handle-create))
           (POST "/websites/:website-id" req (auth/auth-required pages/handle-create))
           (PUT "/websites/:website-id" req (auth/auth-required websites/handle-create-with-pages))
           (POST "/image/:website-id/:page-name/:hash" req (auth/auth-required handle-create-image))
           (POST "/vectors/:website-id/:page-name/*" req (auth/auth-required handle-create-vector))))
;; POST /api/websites
;; POST /api/websites/:website-id
;; POST /api/vectors/:website-id/:page-name/:vector-id`
;; POST /api/image/:image-hash
;; POST /api/test-token (/api/me))

(def routes
  (-> #'plugin-routes
      wrap-params
      wrap-multipart-params
      (wrap-authentication auth/plugin-auth-backend)))

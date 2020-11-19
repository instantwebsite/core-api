(ns instant-website.apis.admin
  (:require
    [clojure.tools.logging :refer [log]]
    [clojure.pprint :refer [pprint]]
    [clojure.edn :as edn]
    ;;
    [compojure.core :refer [defroutes
                            context
                            GET POST PUT DELETE]]
    [buddy.auth.middleware :refer [wrap-authentication]]
    [crux.api :as crux]
    ;;
    [instant-website.auth :as auth]
    [instant-website.db :as db]))

(defn get-tx-log [crux-node]
  (with-open [tx-log-iterator (.openTxLog crux-node nil true)]
    (let [result (iterator-seq tx-log-iterator)]
      result)))

(defn read-body [req]
  (if (:body req)
    (edn/read-string (-> req :body .bytes slurp))
    {}))

;; Admin routes, disabled in production
(defroutes admin-routes
  (context "/api" []
    (context "/aaaaaaaaaaaaa" []
             (GET "/attribute-stats" []
                  (auth/auth-required
                    (fn [req]
                      (log :warn (str "admin data access by " (-> req :identity :user-id)))
                      (prn-str
                        (crux/attribute-stats (:crux req))))))
             (GET "/tx-logs" []
                  (auth/auth-required
                    (fn [req]
                      (log :warn (str "admin data access by " (-> req :identity :user-id)))
                      (prn-str
                        (get-tx-log (:crux req))))))
             (GET "/entity/:id" [id]
                  (auth/auth-required
                    (fn [req]
                      (log :warn (str "admin data access by " (-> req :identity :user-id)))
                      (prn-str
                        (->
                          (crux/q
                            (crux/db (:crux req))
                            {:find '[(eql/project ?entity [*])]
                             :where '[[?entity :crux.db/id ?id]]
                             :args [{'?id id}]})
                          first
                          first)))))
             (PUT "/entity/:id" []
                  (auth/auth-required
                    (fn [req]
                      (let [new-entity (read-body req)]
                        (log :warn (str "admin data access by " (-> req :identity :user-id)))
                        (pprint new-entity)
                        (do
                          (crux/await-tx
                            (:crux req)
                            (db/put! (:crux req) new-entity))
                          (prn-str new-entity))))))
             (GET "/recent-queries" []
                  (auth/auth-required
                    (fn [req]
                      (log :warn (str "admin data access by " (-> req :identity :user-id)))
                      (prn-str
                        (into []
                          (crux/recent-queries (:crux req)))))))
             (GET "/everything" []
                  (auth/auth-required
                    (fn [req]
                      (log :warn (str "admin data access by " (-> req :identity :user-id)))
                      (prn-str
                        (db/find (:crux req)
                                 '[(eql/project ?website [*])]
                                 '[[?website :crux.db/id ?website-id]]
                                 :many))))))))

(def routes
  (-> #'admin-routes
      (wrap-authentication auth/admin-auth-backend)))


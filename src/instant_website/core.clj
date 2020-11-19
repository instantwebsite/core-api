(ns instant-website.core
  ;; TODO get rid of using :use
  (:use org.httpkit.server)
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :refer [log enabled? logp spy]]
    ;;
    [compojure.core :refer :all]
    [compojure.route :as route]
    [jumblerg.middleware.cors :refer [wrap-cors]]
    [crux.api :as crux]
    [nrepl.server :refer [start-server]]
    [muuntaja.middleware :as muuntaja]
    ;; 
    [instant-website.metrics :as metrics]
    [instant-website.config :refer [config]]

    [instant-website.apis.plugin :as plugin-api]
    [instant-website.apis.dashboard :as dashboard-api]
    [instant-website.apis.admin :as admin-api])
  (:gen-class))

(defonce crux-node (atom nil))

(defn wrap-crux
  {:arglists '([handler crux-node] [handler options crux-node])}
  [handler crux-node]
  (fn [request]
    (handler (assoc-in request [:crux] @crux-node))))

(defn wrap-log-access [handler]
  (fn [request]
    (let [method (:request-method request)
          uri (:uri request)]
      (log :info (format "[%s %s]" method uri))
      (handler request))))

;; (defmacro get-git-version []
;;   `~(clojure.string/trim (slurp "./.git/refs/heads/master")))

(defroutes main-routes
  (ANY "*" [] #'plugin-api/routes)
  (ANY "*" [] #'dashboard-api/routes)
  (ANY "*" [] #'admin-api/routes)
  (route/not-found "Couldn't find that page. What page are you looking for really?"))

(def crux-opts
  {:my-rocksdb {:crux/module 'crux.rocksdb/->kv-store
                :db-dir (io/file (:db-path config))}
   :crux.metrics.prometheus/http-exporter {:port 9999}
   :crux/tx-log {:kv-store :my-rocksdb}
   :crux/document-store {:kv-store :my-rocksdb}
   :crux/index-store {:kv-store :my-rocksdb}})

(defn init! []
  (reset! crux-node (crux/start-node crux-opts)))

(def app (-> #'main-routes
             wrap-log-access
             (wrap-crux crux-node)
             (muuntaja/wrap-format)
             (wrap-cors #".*")
             (metrics/wrap)))

(def server-instance (atom nil))
(def repl-instance (atom nil))

(defn stop-server! []
  (when-not (nil? @server-instance)
    (reset! server-instance (@server-instance))))

(def listen-address
  (if (= (:env config) "development")
    "0.0.0.0"
    "127.0.0.1"))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (log :info (format "[-main] running repl %s:47326" listen-address))
  (reset! repl-instance (start-server :bind listen-address :port 47326))
  (log :info "[-main] starting crux")
  (init!)
  (log :info (format "[-main] running http server %s:8080" listen-address))
  (reset! server-instance (run-server #'app {:ip listen-address
                                             :port 8080
                                             :thread (Integer/parseInt (:httpkit-threads config))})))

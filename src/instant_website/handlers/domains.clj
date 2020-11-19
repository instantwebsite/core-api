(ns instant-website.handlers.domains
  (:require
    [clojure.edn :as edn]
    [clojure.tools.logging :refer [log]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :refer [includes?
                            split
                            join]]
    [clojure.spec.alpha :as s]
    ;;
    [crux.api :as crux]
    [cheshire.core :refer [generate-string parse-string]]
    [iapetos.collector.fn :as fn]
    [cawdy.core :as cawdy]
    ;;
    [instant-website.db :as db]
    [instant-website.auth :as auth]
    [instant-website.config :refer [config]]
    [instant-website.metrics :as metrics])
  (:import java.io.InputStreamReader))

;; Ideally we want to replace this with something that uses the same nameservers
;; to lookup IPs as we use in general for Instant Website, but Google does have
;; a very nice API that is a lot more reliable than the nameservers we've tried
;; so far.
(defn txt-records [domain]
  (into []
        (map (fn [i]
               (join
                 (rest (butlast (:data i)))))
             (-> (str "https://dns.google/resolve?name="
                      domain
                      "&type=txt")
                 (slurp)
                 (parse-string true)
                 :Answer))))

(defn a-records [domain]
  (try
    (let [results (atom [])
          query-res
          (-> (javax.naming.directory.InitialDirContext.)
              (.getAttributes (str "dns://"
                                   (:dns-resolver config)
                                   "/" domain))
              (.get "A"))]
      (if query-res
        (let [enum (.getAll query-res)]
          (while (.hasMore enum)
            (swap! results conj (.next enum)))
          @results)
        nil))
    (catch Exception err
      (pprint err)
      [])))

(defn filter-instantwebsite-records [records]
  (filter #(includes? % "instantwebsite=") records))

(defn take-verification-code [records]
  ;; TODO we only take the first record, should warn the user
  ;; in case we find multiple records here
  (if (> (count records) 0)
    (let [record (first records)
          splitted (split record #"=")]
      (last splitted))
    nil))

(defn domain->verification-code [domain]
  (try
    (-> (txt-records domain)
        (filter-instantwebsite-records)
        (take-verification-code))
    (catch Exception err
      (pprint err)
      nil)))

;; Move to db ns
(defn get-domain [node domain-id email]
  (-> node
      crux/db
      (crux/q
        {:find '[(eql/project ?domain [:crux.db/id
                                       :domain/id
                                       :domain/hostname
                                       :domain/website-id
                                       :domain/website-revision
                                       :domain/verification-code
                                       :domain/verified?
                                       :domain/auto-update?
                                       :user/email])]
         :where '[[?domain :domain/id ?id]
                  [?domain :user/email ?email]]
         :args [{'?id domain-id
                 '?email email}]})))

(defn read-body [req]
  (if (:dev-body req)
    (:dev-body req)
    (if (:body req)
      (edn/read-string (-> req :body .bytes slurp))
      {})))

(defn handle-create [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)

        user (db/user-id->user crux-node user-id)
        domain (db/->domain user)

        body (read-body req)

        hostname (:domain/hostname body)
        auto-update? (or (:domain/auto-update? body) false)

        new-domain (db/mut domain
                           {:domain/hostname hostname
                            :domain/auto-update? auto-update?})]

    (if (s/valid? :instant-website.db/domain new-domain)
      (do
        (crux/await-tx
          crux-node
          (db/put! crux-node new-domain))
        {:status 201
         :body new-domain})
      {:status 400
       :body {:error? true
              :message (s/explain-data :instant-website.db/domain new-domain)}})))

(fn/instrument! metrics/registry #'handle-create)

(defn handle-show [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        domain-id (-> req :route-params :domain-id)
        res (db/domain-id->domain crux-node user-id domain-id)]
    {:status (if res 200 404)
     :body res}))

(fn/instrument! metrics/registry #'handle-show)

;; TODO should check that caddy actually runs when we first setup connection
;; TODO make sure we can use caddy in dev as well
(def caddy-conn (cawdy/connect "http://localhost:2019"))

;; coerce into boolean
(def disable-ssl? (#{"true"} (:cawdy-disable-ssl config)))

(def caddy-server (merge {:listen [(:cawdy-port config)]}
                         (when-not disable-ssl?
                           {:automatic_https {}})))

(defn domain-website-on-disk [domain]
  (format "%s/%s/%s"
          (:websites-storage-path config)
          (:domain/website-id domain)
          (:domain/website-revision domain)))

(defn update-caddy-config [domain]
  (cawdy/create-server caddy-conn :srv0 caddy-server)
  (cawdy/add-route caddy-conn
                   :srv0
                   (:domain/hostname domain)
                   :files
                   {:root (domain-website-on-disk domain)}))


(defn handle-update [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        body (read-body req)

        domain-id (-> req :route-params :domain-id)

        domain (db/domain-id->domain crux-node user-id domain-id)]
    (if-not domain
      {:status 404}
      (let [website-id (or (:domain/website-id body) (:domain/website-id domain))
            website-revision (or (:domain/website-revision body) (:domain/website-revision domain))
            auto-update? (or (:domain/auto-update? body) (:domain/auto-update? domain) false)

            new-domain (db/mut domain
                               {:domain/website-id website-id
                                :domain/website-revision website-revision
                                :domain/auto-update? auto-update?})]
        (when (:domain/verified? new-domain)
          (update-caddy-config new-domain))
        (db/await+put! crux-node new-domain)
        {:status 200
         :body new-domain}))))

(fn/instrument! metrics/registry #'handle-update)

(defn handle-delete [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        domain-id (-> req :route-params :domain-id)
        domain (db/domain-id->domain crux-node user-id domain-id)]
    (if domain
      (do
        (log :info "Found domain, deleting")
        (when (:domain/verified? domain)
          (log :info "Domain was verified before, remove from caddy")
          (cawdy/remove-route
            caddy-conn
            :srv0
            (:domain/hostname domain)))
        {:status 200
         :body (crux/await-tx crux-node (db/delete! crux-node domain-id))})
      {:status 404})))

(fn/instrument! metrics/registry #'handle-delete)

(defn handle-index [req]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)]
    {:status 200
     :body (db/user-id->domains crux-node user-id)}))

(fn/instrument! metrics/registry #'handle-index)

(defn set-domain-as-verified! [crux-node domain]
  (let [new-domain (db/mut domain {:domain/verified? true})]
    (update-caddy-config new-domain)
    (crux/await-tx
      crux-node
      (db/put! crux-node new-domain))
    new-domain))

;; TODO supposed to make sure the right A/AAAA records are set, but no need yet for this
(defn handle-validate [req]
  (let [crux-node (:crux req)
        domain-id (-> req :route-params :domain-id)
        email (-> req :identity :user)
        domain (-> (get-domain crux-node domain-id email)
                   first
                   first)]
    (prn-str
      (if (= (a-records (:domain/hostname domain))
             (:wanted-dns-a-records config))
        true
        false))))

(fn/instrument! metrics/registry #'handle-validate)

(defn handle-verify [req]
  (let [crux-node (:crux req)
        domain-id (-> req :route-params :domain-id)
        user-id (-> req :identity :user-id)
        domain (db/domain-id->domain crux-node user-id domain-id)
        current-verification-code (:domain/verification-code domain)
        dns-verification-code
        (domain->verification-code (:domain/hostname domain))]
    (if domain
      {:status 200
       :body 
       (if (and (not (nil? current-verification-code))
                (not (nil? dns-verification-code))
                (= current-verification-code dns-verification-code))
         (set-domain-as-verified! crux-node domain)
         domain)}
      {:status 404})))

(fn/instrument! metrics/registry #'handle-verify)

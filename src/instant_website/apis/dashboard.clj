(ns instant-website.apis.dashboard
  (:require
    [clojure.tools.logging :refer [log]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :refer [split]]
    ;;
    [compojure.core :refer [defroutes
                            context
                            GET POST PUT DELETE]]
    [iapetos.collector.fn :as fn]
    [buddy.auth.middleware :refer [wrap-authentication]]
    [cheshire.core :refer [parse-string]]
    [crux.api :as crux]
    [buddy.core.mac :as buddy-mac]
    [buddy.core.codecs :as buddy-codecs]
    ;;
    [instant-website.auth :as auth]
    [instant-website.db :as db]
    [instant-website.config :refer [config]]
    [instant-website.metrics :as metrics]
    [instant-website.stripe :as stripe]
    [instant-website.handlers.websites :as websites]
    [instant-website.handlers.domains :as domains]))

(defn read-json-body [req]
  (parse-string (-> req :body .bytes slurp) true))

;; Cyclic dependency db -> stripe -> db
;; So, putting it here for now...
(def product-plans {(:stripe-pro-product-id config) :pro
                    "free" :free})

(defn event->product [event]
  (-> event
      :data
      :object
      :items
      :data
      first
      :plan
      :product))

(defn customer-subscription-updated [crux-node event]
  (let [object (-> event :data :object)
        status (:status object)
        product (event->product event)
        customer-id (:customer object)
        user (db/customer-id->user crux-node customer-id)]
    (if user
      (crux/await-tx
        crux-node
        (db/put! crux-node
                 (db/mut user {:user/plan (get product-plans (if (= status "active")
                                                               product
                                                               "free"))})))
      {:status 200
       :body "Ok!"})))

(defn customer-subscription-deleted [crux-node event]
  (let [object (-> event :data :object)
        customer-id (:customer object)
        user (db/customer-id->user crux-node customer-id)]
    (if user
      (crux/await-tx
        crux-node
        (db/put! crux-node
                 (db/mut user {:user/plan :free})))
      {:status 200
       :body "Ok!"})))

;; Steps to verify:
;; - Take timestamp + v1 signature from stripe-signature header
;; - Create signed_payload "$timestamp.$json-payload" (note the dot (.))
;; - hmac+sha256 of signed_payload
;; - Compare with endpoints secret key
(defn signature-correct?
  "Verifies STRIPE-SIGNATURE header from request coming from Stripe via webhooks"
  [req]
  (let [header-value (get-in req [:headers "stripe-signature"])
        splitted (split header-value #",")
        params (map #(split % #"=") splitted)
        timestamp (-> params first second)
        signature (-> params second second)
        received-signature (buddy-codecs/bytes->hex
                             (buddy-mac/hash (str timestamp "." (-> req :body .bytes slurp))
                                             {:key (:stripe-webhook-secret config)
                                              :alg :hmac+sha256}))]
    (log :info (str "signature=" signature))
    (log :info (str "received-signature=" received-signature))
    (= signature received-signature)))

(def webhook-ips
  ["3.18.12.63"
   "3.130.192.231"
   "13.235.14.237"
   "13.235.122.149"
   "35.154.171.200"
   "52.15.183.38"
   "54.187.174.169"
   "54.187.205.235"
   "54.187.216.72"
   "54.241.31.99"
   "54.241.31.102"
   "54.241.34.107"])

(defn ip-from-stripe?
  "Checks if the request came from Stripes webhook servers.
   List available at https://stripe.com/docs/ips#webhook-ip-addresses"
  [req]
  (let [remote-addr (:remote-addr req)]
    (some #(= remote-addr %) webhook-ips)))

(defn react-to-stripe-webhook!
  [req]
  (if (ip-from-stripe? req)
    (if (signature-correct? req)
      (let [event (read-json-body req)
            crux-node (:crux req)]
        (log :info (str "received stripe event " (:type event)))
        (condp = (:type event)
          "customer.subscription.updated" (customer-subscription-updated crux-node event)
          "customer.subscription.deleted" (customer-subscription-deleted crux-node event)
          (:type event)))
      (do
        (log :info (str "stripe event without proper signature received"))
        {:status 403
         :body "Failed to verify signature"}))
    (do
      (log :info (str "stripe event didnt come from Stripes servers"))
      {:status 403
       :body "IP not in allowlist"})))

(fn/instrument! metrics/registry #'react-to-stripe-webhook!)
(fn/instrument! metrics/registry #'customer-subscription-deleted)
(fn/instrument! metrics/registry #'customer-subscription-updated)

;; These routes are for the frontend
;; - Uses EDN for request/response bodies
;; - Uses stateless JWT auth

(defroutes dashboard-routes
  (context "/api" []
    (context "/login" []
             (POST "/refresh" req "ok")
             (POST "/email/:email" req (auth/email-code-handler req))
             (POST "/email/:email/code/:code" req (auth/handle-trade-login-code! req)))
    (GET "/me" req (auth/auth-required auth/me-handler))

    (GET "/websites" req (auth/auth-required websites/handle-index))
    (POST "/websites" req (auth/auth-required websites/handle-create))

    (GET "/websites/:website-id" req (auth/auth-required websites/handle-show))
    (GET "/websites/:website-id/history" req (auth/auth-required websites/handle-history))

    ;; TODO need to rename this route, doesn't make any sense. Should be
    ;; mutating just the pages in the website, for the plugin
    (PUT "/websites/:website-id" req (auth/auth-required websites/handle-update))
    (DELETE "/websites/:website-id" req (auth/auth-required websites/handle-delete))

    (POST "/checkout-session" req (auth/auth-required stripe/handle-create-session))
    (POST "/portal-session" req (auth/auth-required stripe/handle-create-portal-session))

    (GET "/domains" req (auth/auth-required domains/handle-index))
    (POST "/domains" req (auth/auth-required domains/handle-create))
    (PUT "/domains/:domain-id" req (auth/auth-required domains/handle-update))
    (GET "/domains/:domain-id" req (auth/auth-required domains/handle-show))
    (DELETE "/domains/:domain-id" req (auth/auth-required domains/handle-delete))
    (POST "/domains/:domain-id/verify" req (auth/auth-required domains/handle-verify))
    (POST "/domains/:domain-id/validate" req (auth/auth-required domains/handle-validate))

    (GET "/websites/:website-id/:website-version/exports/html" req
         (websites/handle-zip-export req))

    (POST "/steamy-stripe-webhook-handler"
          req
          (react-to-stripe-webhook!
            req))))

(def routes
  (-> #'dashboard-routes
      (wrap-authentication auth/api-auth-backend)))


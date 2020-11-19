(ns instant-website.stripe
  (:require
    [clojure.pprint :refer [pprint] :rename {pprint pp}]
    [clojure.tools.logging :refer [log]]
    [clojure.core.memoize :refer [fifo]]
    ;;
    [org.httpkit.client :as http]
    [cheshire.core :refer [parse-string generate-string]]
    [paraman.core :refer [convert]]
    [iapetos.collector.fn :as fn]
    ;;
    [instant-website.config :refer [config]]
    [instant-website.metrics :as metrics]))

(def stripe-url "https://api.stripe.com/v1")

(def stripe-secret-key (:stripe-sk config))

(def available-plans [:free :pro])

(def free-plan :free)

(def pro-plan :free)

(defn create-url [path]
  (str (:frontend-host config) path))

(def urls {:checkout {:cancel-url (create-url "/pricing/cancel")
                      :success-url (create-url "/pricing/success")}
           :portal {:return-url (create-url "/profile")}})

(def pro-price-id (:stripe-pro-price-id config))

;; TODO should not create customer if already exists, throw exception
;; or reuse existing customer
(defn create-customer! [user-id email]
  (log :debug (format "creating stripe customer for user %s with email %s"
                      user-id email))
  (let [opts {:headers {"Authorization" (str "Bearer " stripe-secret-key)}
              :form-params {:email email
                            :metadata {:user-id user-id}}}]
    (-> @(http/post (str stripe-url "/customers")
                    opts)
        :body
        (parse-string true))))

(defn checkout-session-params [tax-rate-id customer-id]
  ;; TODO ugly shortcut, fix proper. Only diff is the tax_rates
  (if (nil? tax-rate-id)
    (convert {:cancel_url (-> urls :checkout :cancel-url)
              :success_url (-> urls :checkout :success-url)
              :mode "subscription"
              :allow_promotion_codes true
              :customer customer-id
              :line_items [{:price (:stripe-pro-price-id config)
                            :quantity 1}]
              :payment_method_types ["card"]})
    (convert {:cancel_url (-> urls :checkout :cancel-url)
              :success_url (-> urls :checkout :success-url)
              :mode "subscription"
              :allow_promotion_codes true
              :customer customer-id
              :line_items [{:price (:stripe-pro-price-id config)
                            :quantity 1
                            :tax_rates [tax-rate-id]}]
              :payment_method_types ["card"]})))

(def ipinfo-token (:ipinfo-token config))

(defn ip->country [ip]
  (-> @(http/get (str "http://ipinfo.io/" ip)
                 {:insecure? true ;; TODO somehow, cert not valid for ipinfo...
                  :user-agent "Instant-Website"
                  :headers {"Accept" "application/json"
                            "Authorization" (str "Bearer " ipinfo-token)}})
      :body
      (parse-string true)
      :country))

(defn -stripe-tax-rates
  ([]
   (-stripe-tax-rates nil []))
  ([last-id collected]
   (let [opts {:headers {"Authorization" (str "Bearer " stripe-secret-key)}}
         starting-after (if last-id (str "&starting_after=" last-id) nil)
         res @(http/get (str stripe-url
                             (if starting-after
                               (str "/tax_rates?active=true&limit=1000" starting-after)
                               "/tax_rates?active=true&limit=1000"))
                        opts)
         body (:body res)
         parsed (parse-string body true)
         has-more? (:has_more parsed)]
     (if has-more?
       (-stripe-tax-rates (-> parsed :data last :id) (concat collected (:data parsed)))
       (concat collected (:data parsed))))))

(def stripe-tax-rates (fifo -stripe-tax-rates))

(defn ip->tax-rate [ip]
  (let [country-code (ip->country ip)
        tax-rate (some
                   #(when (= (:jurisdiction %) country-code)
                      %)
                   (stripe-tax-rates))]
      tax-rate))

(defn stripe-create-checkout-session [ip-address customer-id]
  (let [params (checkout-session-params (-> ip-address
                                            ip->tax-rate
                                            :id)
                                        customer-id)
        opts {:headers {"Authorization" (str "Bearer " stripe-secret-key)}
              :body params}]
    (println "[stripe-create-checkout-session]")
    (pp params)
    (-> @(http/post (str stripe-url "/checkout/sessions")
                    opts)
        :body
        (parse-string true))))

(defn stripe-create-portal-session [customer-id]
  (let [opts {:headers {"Authorization" (str "Bearer " stripe-secret-key)}
              :body (convert {:customer customer-id
                              :return_url (-> urls :portal :return-url)})}]
    (-> @(http/post (str stripe-url "/billing_portal/sessions")
                    opts)
        :body
        (parse-string true))))

(defn handle-create-session [req]
  (log :info "[handle-create-session]")
  (log :info (dissoc req :crux))
  (let [ip (or (get-in req [:headers ":CF-Connecting-IP"])
               (:remote-addr req)
               "81.43.193.199") ;; Default IP, Spain
        ip (if (= ip "127.0.0.1")
             "81.43.193.199"
             ip)
        ;; ip "5.28.16.121"
        tax-rate (ip->tax-rate ip)
        customer-id (-> req :identity :user :user/stripe-id)]
    {:status 201
     :body (stripe-create-checkout-session ip customer-id)}))

(fn/instrument! metrics/registry #'handle-create-session)

(defn handle-create-portal-session [req]
  (let [customer-id (-> req :identity :user :user/stripe-id)]
    {:status 201
     :body (stripe-create-portal-session customer-id)}))

(fn/instrument! metrics/registry #'handle-create-portal-session)

(defn stripe-events []
  (let [opts {:headers {"Authorization" (str "Bearer " stripe-secret-key)}
              :query-params {:limit 100}}]
    (-> @(http/get (str stripe-url
                        "/events")
                   opts)
        :body
        (parse-string true)
        :data)))

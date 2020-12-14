(ns instant-website.config
  (:require [dotenv :as dotenv]))

(defn env-or-default [k default-value]
  (or (System/getenv k)
      (dotenv/env k)
      default-value))

;; Tuple is: [env var, default value]
(def vars
  {:db-path ["IW_DB_PATH"
             "/tmp/rocksdb"]

   :storage-path ["IW_STORAGE_PATH"
                  "/tmp/"]
   :env ["IW_ENV"
         "development"]

   :tests ["IW_TESTS"
           "no"]

   :frontend-host ["IW_FRONTEND_HOST"
                   "http://localhost:8081"]

   :stripe-pk ["IW_STRIPE_PK"
               "pk_test_xxx"]

   :stripe-sk ["IW_STRIPE_SK"
               "sk_test_xxx"]

   :stripe-pro-price-id ["IW_STRIPE_PRO_PRICE_ID"
                         "price_1Ha2EXKE4zmFNORmJGUURtzv"]

   :stripe-pro-product-id ["IW_STRIPE_PRO_PRODUCT_ID"
                           "prod_I9Xwa6EyGx36On"]

   :stripe-webhook-secret ["IW_STRIPE_WEBHOOK_SECRET"
                           "whsec_xxx"]

   :ipinfo-token ["IW_IPINFO_TOKEN"
                  "xxx"]

   :websites-title ["IW_WEBSITES_TITLE"
                    "InstantWebsite.app"]

   :websites-storage-path ["IW_WEBSITES_STORAGE_PATH"
                           "/tmp/instantwebsites"]

   :cawdy-port ["IW_CAWDY_PORT"
                ":8888"]

   :cawdy-disable-ssl ["IW_CAWDY_DISABLE_SSL"
                       "true"]

   :admin-user ["IW_ADMIN_USER"
                "victorbjelkholm@gmail.com"]

   :smtp-host ["IW_SMTP_HOST"
               "smtp.sendgrid.net"]
   :smtp-port ["IW_SMTP_PORT"
               "587"]
   :smtp-user ["IW_SMTP_USER"
               "apikey"]
   :smtp-pass ["IW_SMTP_PASS"
               "xxx"]

   :dns-resolver ["IW_DNS_RESOLVER"
                  "208.67.222.222"]

   :wanted-dns-a-records ["IW_WANTED_DNS_A_RECORDS"
                          "46.101.125.179"]

   :httpkit-threads ["IW_HTTPKIT_THREADS"
                     "12"]})

(def config
  (reduce
    (fn [acc [k [kk v]]]
      (assoc acc k (env-or-default kk v)))
    {}
    vars))

(def env-key
  (get {"development" :development
        "production" :production}
       (:env config)))

(comment
  (identity config)
  (identity env-key)
  (:frontend-host config)
  (:db-path config)
  (:smtp-host config))

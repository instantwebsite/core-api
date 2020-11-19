#!/usr/bin/env bb
(require
  '[clojure.pprint :refer [pprint]]
  '[babashka.curl :as curl]
  '[cheshire.core :as json])

;; Heads up! This file is run with babashka, not with clj

(def stripe-sk (System/getenv "STRIPE_SK"))

(when (empty? stripe-sk)
  (throw (Exception. "STRIPE_SK was empty, you need a valid Stripe Secret Key there bo")))

;; Get tax-rates.json from vatsense
;; Something like the following should do the trick:
;; curl --url 'https://api.vatsense.com/1.0/rates' \
;;      --user 'user:pw' \
;; | jq . > resources/tax-rates.json

(def tax-raw (slurp "./resources/tax-rates.json"))

(defn get-tax-name [vatsense-tax]
  (str
    (if (:eu vatsense-tax) "VAT" "Tax")
    " "
    (:country_name vatsense-tax)))

(defn vatsense->stripe [vatsense-tax]
  {:display_name (get-tax-name vatsense-tax)
   :inclusive true
   :jurisdiction (:country_code vatsense-tax)
   :percentage (-> vatsense-tax
                   :standard
                   :rate)})

(defn ppret [thing]
  (pprint thing)
  thing)

;; take but args the other way
(defn ttake [a n]
  (take n a))

;; map but args the other way
(defn mmap [a f]
  (map f a))

(def vatsense-taxes
  (-> tax-raw
      (json/parse-string true)
      :data
      ;; (ttake 1)
      ;; (ppret)
      (mmap vatsense->stripe)
      (ppret)))

(defn create-query-params [tax-rate]
  {"display_name" (:display_name tax-rate)
   "inclusive" (if (:inclusive tax-rate) "true" "false")
   "percentage" (str (:percentage tax-rate))
   "jurisdiction" (:jurisdiction tax-rate)})

(defn create-stripe-tax-rate [tax-rate]
  (println (str "Creating for " (:display_name tax-rate)))
  (-> (curl/post
        "https://api.stripe.com/v1/tax_rates"
        {:basic-auth [stripe-sk ""]
         :query-params (create-query-params tax-rate)})
      :status
      (ppret)))

(println "Creating tax rates")
(doseq [tax-rate vatsense-taxes]
  (create-stripe-tax-rate tax-rate))

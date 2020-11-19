(ns instant-website.handlers.domains-test
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.java.io :refer [input-stream]]
    [clojure.test :refer :all]
    [clojure.edn :refer [read-string]]
    ;;
    [crux.api :as crux]
    ;;
    [instant-website.db :as db]
    [instant-website.handlers.domains :as domains]
    [instant-website.test-utils :as tu]))

(defn ->req [c u opt]
  (merge {:crux c
          :identity {:user-id (:crux.db/id u)}}
         opt))

(defn update-domain [c u old-d new-d]
  (-> (->req c u {:route-params {:domain-id (:crux.db/id old-d)}
                  :dev-body (merge old-d new-d)})
      (domains/handle-update)))

(defn get-domain [c u d-id]
  (-> (->req c u {:route-params {:domain-id d-id}})
      (domains/handle-show)))

(deftest domain-handlers-test
  (testing "has default attributes"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          domain (tu/create-test-domain c u)]
      (is (= "example.com" (:domain/hostname domain)))
      (is (= false (:domain/auto-update? domain)))
      (is (= (:crux.db/id u) (:domain/user-id domain)))))
  (testing "retrieve single domain"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          domain (tu/create-test-domain c u)
          req (->req c u {:route-params {:domain-id (:crux.db/id domain)}})
          res (domains/handle-show req)]
      (is (= domain (:body res)))))
  (testing "delete domain"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          domain (tu/create-test-domain c u)
          req (->req c u {:route-params {:domain-id (:crux.db/id domain)}})
          res (domains/handle-show req)]
      (is (= domain (:body res)))
      (domains/handle-delete req)
      (is (= {:status 404, :body nil} (domains/handle-show req)))))
  (testing "retrieve list of domains"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          domain (tu/create-test-domain c u)
          req (->req c u {})
          res (domains/handle-index req)]
      (is (= [domain] (:body res)))))
  (testing "update a domain"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          domain (tu/create-test-domain c u)
          req (->req c u {:route-params {:domain-id (:crux.db/id domain)}
                          :dev-body {:domain/auto-update? false}})
          update-res (domains/handle-update req)
          show-req (->req c u {:route-params {:domain-id (:crux.db/id domain)}})
          show-res (domains/handle-show show-req)
          
          expected-domain (assoc domain :domain/auto-update? false)]
      (is (= expected-domain (:body update-res)))
      (is (= expected-domain (:body show-res)))))
  (testing "cant update other users domain"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          wrong-user (tu/create-test-user c)
          domain (tu/create-test-domain c u)
          ;; Only change compared to "update-a-domain" test is that we're
          ;; using "wrong-user" instead of "u" below
          req (->req c wrong-user {:route-params {:domain-id (:crux.db/id domain)}
                                   :dev-body {:domain/auto-update? false}})
          update-res (domains/handle-update req)
          show-req (->req c u {:route-params {:domain-id (:crux.db/id domain)}})
          show-res (domains/handle-show show-req)]
      (is (= {:status 404} update-res))
      (is (= domain (:body show-res))))))

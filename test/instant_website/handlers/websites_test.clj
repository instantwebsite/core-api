(ns instant-website.handlers.websites-test
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.java.io :refer [input-stream]]
    [clojure.test :refer :all]
    [clojure.edn :refer [read-string]]
    ;;
    [crux.api :as crux]
    [cheshire.core :refer [parse-string generate-string]]
    ;;
    [instant-website.db :as db]
    [instant-website.handlers.websites :as websites]
    [instant-website.test-utils :as tu]))

(defn update-website [c u old-w new-w]
  (-> {:crux c
       :identity {:user-id (:crux.db/id u)}
       :route-params {:website-id (:crux.db/id old-w)}
       :dev-body (merge old-w new-w)}
      (websites/handle-update)))

(defn get-website [c u w-id]
  (-> {:crux c
       :identity {:user-id (:crux.db/id u)}
       :route-params {:website-id w-id}}
      (websites/handle-show)))

(deftest website-handlers-test
  (testing "has default attributes"
    (let [c (tu/crux-node)
          u (tu/create-test-user c)
          website (tu/create-test-website c u)]
      (is (= "Test" (:website/name website)))
      (is (= "testing" (:website/startpage website)))
      (is (= (:crux.db/id u) (:website/user-id website)))))
  (let [c (tu/crux-node)
        user (tu/create-test-user c)
        website (tu/create-test-website c user)]
    (testing "Updating name"
      (is (not (empty? (:website/name website))))
      (let [new-website (update-website c user website {:website/name "cool"})]
        (is (= "cool" (:website/name new-website)))))
    (testing "Can't update with other users ID"
      (is (not (empty? (:website/name website))))
      (let [other-user (tu/create-test-user c)
            update-res (update-website c other-user website {:website/name "cool2"})
            website-refetched (:body (get-website c user (:crux.db/id website)))]
        (is (= 403 (:status update-res)))
        (is (= "cool" (:website/name website-refetched)))))))

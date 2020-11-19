(ns instant-website.auth-test
  (:require
    [clojure.test :refer :all]
    [clojure.pprint :refer [pprint]]
    [crux.api :as crux]
    [instant-website.auth :as auth]
    [instant-website.test-utils :as tu]
    [instant-website.db :as db]))

(defn assert-tokens [t]
  (is (= 23 (count (:tokens/plugin t))))
  (is (= 23 (count (:tokens/api t))))
  (is (= \f (first (:tokens/plugin t))))
  (is (= \a (first (:tokens/api t)))))

(deftest create-tokens-test
  (testing "Create new tokens"
    (let [c (tu/crux-node)
          login-code (db/->login-code "test@example.com")
          tokens (db/->tokens)]
      (assert-tokens tokens)))
  (testing "Create new tokens via trade-login-code!"
    (let [c (tu/crux-node)
          login-code (db/->login-code "test@example.com")
          _ (tu/await-put! c login-code)
          req {:crux c
               :route-params {:email (:login-code/email login-code)
                              :code (:login-code/code login-code)}}
          profile (:body (auth/handle-trade-login-code! req))]
      (assert-tokens profile)
      (testing "Authenticate with API Token"
        (let [api-req {:crux c
                       :identity {:user-id (:crux.db/id profile)}}
              auth-res (auth/api-token-authfn
                         {:crux c}
                         (:tokens/api profile))
              me-res (:body (auth/me-handler api-req))]
          (is (= (:user-id auth-res)
                 (:crux.db/id profile)))
          (is (= (-> me-res :user :crux.db/id)
                 (:crux.db/id profile)))))
      (testing "Authenticate with Plugin Token"
        (let [api-req {:crux c
                       :identity {:user-id (:crux.db/id profile)}}
              auth-res (auth/plugin-token-authfn
                         {:crux c}
                         (:tokens/plugin profile))
              me-res (:body (auth/plugin-me-handler api-req))]
          (is (= (:user-id auth-res)
                 (:crux.db/id profile)))
          (is (= (:crux.db/id me-res)
                 (:crux.db/id profile)))))))
  (testing "Authorize resources"
    (testing "check ownership"
      (let [user-id "testing"
            resource {:name "hello"
                      :user-id "testing"}
            wrapped-func (fn [] resource)]
        (is (= resource (auth/check-ownership user-id resource :user-id wrapped-func)))
        (is (= {:status 403} (auth/check-ownership "falseid" resource :user-id wrapped-func)))))
    (testing "check website ownership"
      (let [c (tu/crux-node)
            user (tu/create-test-user c)
            wrong-user (tu/create-test-user c)
            website (tu/create-test-website c user)
            req {:crux c
                 :route-params {:website-id (:crux.db/id website)}}]
        (testing "with right user"
          (let [req (assoc-in req [:identity :user-id] (:crux.db/id user))
                res (auth/check-website-ownership req (fn [w] website))]
            (is (= website res))))
        (testing "with wrong user"
          (let [req (assoc-in req [:identity :user-id] (:crux.db/id wrong-user))
                res (auth/check-website-ownership req (fn [w] website))]
            (is (= {:status 403} res))))))))

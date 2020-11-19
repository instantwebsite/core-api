(ns instant-website.db-test
  (:require
    [clojure.test :refer :all]
    [crux.api :as crux]
    [instant-website.test-utils :as tu]
    [instant-website.db :as db]))

(deftest login-code-single-user
  (testing "Find login-code after creation"
    (let [crux-node (tu/crux-node)
          email "test@example.com"
          login-code (tu/create-login-code! crux-node email)]
      (is (= (db/find-login-code crux-node login-code)
             login-code))))
  (testing "Doesn't find login-code after deletion"
    (let [crux-node (tu/crux-node)
          email "test@example.com"
          login-code (tu/create-login-code! crux-node email)]
      (tu/await-delete! crux-node (:crux.db/id login-code))
      (is (= (db/find-login-code crux-node login-code)
             nil)))))

(deftest login-code-multiple-users
  (testing "Two login-codes found after creation"
    (let [crux-node (tu/crux-node)
          email1 "email1@example.com"
          email2 "email2@example.com"
          login-code1 (tu/create-login-code! crux-node email1)
          login-code2 (tu/create-login-code! crux-node email2)]

      (is (not= login-code1 login-code2))

      (is (= (db/find-login-code crux-node login-code1)
             login-code1))

      (is (= (db/find-login-code crux-node login-code2)
             login-code2))

      (tu/await-delete! crux-node (:crux.db/id login-code1))
      (is (= (db/find-login-code crux-node login-code1)
             nil))
      (is (= (db/find-login-code crux-node login-code2)
             login-code2))

      (tu/await-delete! crux-node (:crux.db/id login-code2))
      (is (= (db/find-login-code crux-node login-code1)
             nil))
      (is (= (db/find-login-code crux-node login-code2)
             nil))
      ;; Another user login
      (let [email3 "email3@example.com"
            login-code3 (tu/create-login-code! crux-node email3)]
        (is (= (db/find-login-code crux-node login-code3)
               login-code3))
        (tu/await-delete! crux-node (:crux.db/id login-code3))
        (is (= (db/find-login-code crux-node login-code3)
               nil))))))

(deftest domain-like-test
  (testing "things that looks like domains"
    (let [valid-domains ["hello.world"]]
      (doseq [domain valid-domains]
        (is (true? (db/domain-like? domain))))))
  (testing "things that don't look like domains"
    (let [invalid-domains ["asd!"
                           "asd!.asd"]]
      (doseq [domain invalid-domains]
        (is (false? (db/domain-like? domain)))))))

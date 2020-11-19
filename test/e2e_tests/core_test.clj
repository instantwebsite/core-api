(ns e2e-tests.core-test
  (:require
    [clojure.test :refer :all]
    [clojure.pprint :refer [pprint]]
    [e2e-tests.core :as e2e]
    [instant-website.core :as iw-core]))

(defn should-run-e2e? []
  (= (System/getProperty "RUN_E2E") "true"))

(deftest e2e-tests
  (if (not (should-run-e2e?))
    (println "Skipping E2E tests")
    (do
      (iw-core/-main)
      (e2e/start-server)
      (doseq [test-name e2e/active-tests]
        (let [scenario-name (str "E2E Test - " test-name)]
          (testing scenario-name
            ;; Checking that the difference is 0
            (is (= 0 (:metric (e2e/run-test-file test-name))))))))))

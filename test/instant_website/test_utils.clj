(ns instant-website.test-utils
  (:require
    [crux.api :as crux]
    [instant-website.db :as db]
    [instant-website.handlers.websites :as websites]
    [instant-website.handlers.domains :as domains]))

(defn crux-node []
  (crux/start-node {}))

(defn await-put! [c e]
  (crux/await-tx c (db/put! c e)))

(defn await-delete! [c e]
  (crux/await-tx c (db/delete! c e)))

(defn create-login-code! [c email]
  (let [login-code (db/->login-code email)]
    (await-put! c login-code)
    login-code))

(defn create-test-user [c]
  (let [login-code (db/->login-code "test@example.com")
        tokens (db/->tokens)
        user (db/->user tokens login-code)]
    (doseq [e [login-code tokens user]]
      (await-put! c e))
    user))

(defn create-test-website [c u]
  (-> {:crux c
       :identity {:user-id (:crux.db/id u)}
       :dev-body {:name "Test"
                  :startpage "testing"}}
      (websites/handle-create)
      :body))

(defn create-test-domain [c u]
  (-> {:crux c
       :identity {:user-id (:crux.db/id u)}
       :dev-body {:domain/hostname "example.com"
                  :domain/auto-update? false}}
      (domains/handle-create)
      :body))

(comment
  (def c (crux/start-node {}))
  (create-test-domain c
                      {:crux.db/id "u123"}))

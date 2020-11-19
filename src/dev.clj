(ns dev
  (:require
    [instant-website.db :as db]
    [instant-website.core :as core]))

(defn new-user []
  (let [login-code (db/->login-code "test@example.com")
        token (db/->tokens)
        user (db/->user token login-code)]
    (doseq [t [token user]]
      (db/put! @core/crux-node t))
    {:user user
     :token token}))

(comment
  (new-user)
  (def user (new-user))
  (identity user)
  (db/put! @core/crux-node (:user user))
  (db/put! @core/crux-node (:token user)))

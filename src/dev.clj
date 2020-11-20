(ns dev
  (:require
    [instant-website.db :as db]
    [instant-website.core :as core]
    [cheshire.core :refer [parse-string generate-string]]
    [clj-http.client :as http]))

(defn new-user []
  (let [login-code (db/->login-code "test@example.com")
        token (db/->tokens)
        user (db/->user token login-code)]
    (doseq [t [token user]]
      (db/put! @core/crux-node t))
    {:user user
     :token token}))

(defn create-website [token file]
  (->
    (http/post "http://localhost:8080/plugin-api/websites"
               {:headers {"Authorization" (str "Token " token)}
                :body (slurp (str "e2e/json-payloads/" file ".json"))})
    :body
    (parse-string true)
    :crux.db/id))

(comment
  (new-user)
  (def user (new-user))
  (def figma-token (-> user :token :tokens/plugin))

  (create-website figma-token "filled-container-auto-layout")

  (identity user)
  (db/put! @core/crux-node (:user user))
  (db/put! @core/crux-node (:token user)))

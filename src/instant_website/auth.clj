(ns instant-website.auth
  (:require
    [clojure.pprint :refer [pprint]]
    [tick.alpha.api :as t]
    [clojure.tools.logging :refer [log]]
    [crypto.random :refer [hex] :rename {hex random-hex}]
    [cheshire.core :refer [generate-string]]
    [tarayo.core :as tarayo]
    [buddy.auth.backends :as auth-backends]
    [buddy.auth.accessrules :refer [restrict]]
    [crux.api :as crux]
    ;;
    [instant-website.db :as db]
    [instant-website.config :refer [config]]))

;; At a user level, `email-code-handler` first called with email,
;; email gets sent with code, user trades email+code for tokens
;; in `handle-trade-login-code!`

(defn api-token-authfn [req token]
  (let [res (db/api-token->user-id (:crux req) token)]
    (when res
      (let [user (db/user-id->user (:crux req) res)]
        {:user-id res
         :user user}))))

(def api-auth-backend (auth-backends/token
                        {:authfn #'api-token-authfn}))

;; Plugin Tokens
(defn plugin-token->email [crux-node token]
  (-> (crux/q
        (crux/db crux-node)
        {:find '[email]
         :where '[[user :tokens/plugin plugin-token]
                  [user :tokens/email email]]
         :args [{'plugin-token token}]})
      first
      first))

(defn plugin-token-authfn [req token]
  (let [res (db/plugin-token->user-id (:crux req) token)]
    (when res
      {:user-id res})))

(def plugin-auth-backend (auth-backends/token
                           {:authfn #'plugin-token-authfn}))

(defn admin-token-authfn [req token]
  (let [res (db/api-token->user-id (:crux req) token)]
    (when res
      (let [user (db/user-id->user (:crux req) res)]
        (when (= (:user/email user) (:admin-user config))
          {:user-id res
           :user user})))))

(def admin-auth-backend
  (auth-backends/token
    {:authfn #'admin-token-authfn}))


(defn should-be-authed [req]
  (not (nil? (:identity req))))

(defn on-auth-error [req _]
  (pprint (-> req :identity))
  {:status 403
   :body {:message "Not authenticated"}})

(defn auth-required [handler]
  (restrict handler {:handler should-be-authed
                     :on-error on-auth-error}))

(defn generate-password []
  (random-hex 32))

(defn get-hostname []
  (.getHostName (java.net.InetAddress/getLocalHost)))

(defn get-domain-to-use []
  (:frontend-host config))

(defn construct-link [domain email pw return-to]
  (if return-to
    (format "%s/authenticate?%s#%s/%s"
            domain return-to email pw)
    (format "%s/authenticate#%s/%s"
            domain email pw)))

(defn email-html-body-with-pw [email pw return-to]
  ;; TODO update url
  (let [domain (:frontend-host config)
        link (construct-link domain email pw return-to)]
    (format "<html><head><meta charset=\"utf-8\"/></head><body>
            <div style=\"font-family: Verdana, sans-serif;\">
            <h1>Email Password</h1>
            <p>We have received a request to login with this email address (%1$s)</p>
            <p><a href=\"%2$s\">Click here to login </a></p>
            <p>Or copy-paste the following URL in the address bar of your browser:</p>
            <p>%2$s</p>
            </div></body></html>"
            email
            link)))

(defn email-text-body-with-pw [email pw return-to]
  ;; TODO update url
  (let [domain (:frontend-host config)
        link (construct-link domain email pw return-to)]
    (format "Email Password

            We have received a request to login with this email address (%1$s)

            Navigate to this URL to login to InstantWebsite
            %2$s"
            email
            link)))

(defn send-email! [to html-body text-body]
  ;; TODO replace with config
  (with-open [conn (tarayo/connect {:host (:smtp-host config)
                                    :port (Integer/parseInt (:smtp-port config))
                                    :user (:smtp-user config)
                                    :password (:smtp-pass config)})]
    (tarayo/send! conn {:from "\"InstantWebsite\" <login-codes@instantwebsite.app>"
                        :to to
                        :subject "Your login code to InstantWebsite.app"
                        :multipart "alternative"
                        :body [{:content-type "text/plain" :content text-body}
                               {:content-type "text/html" :content html-body}]})))

(defn email->user-id [crux-node email]
  (->
    (crux/q (crux/db crux-node)
            {:find '[user-id]
             :where '[[user-id :user/email email]]
             :args [{'email email}]})
    first
    first))

(defn email->tokens-id [crux-node email]
  (->
    (crux/q (crux/db crux-node)
            {:find '[tokens]
             :where '[[tokens :tokens/email email]]
             :args [{'email email}]})
    first
    first))

(defn email->tokens [crux-node email]
  (let [tokens (crux/entity
                 (crux/db crux-node)
                 (email->tokens-id crux-node email))]
    tokens))

(defn create-tokens! [crux-node email]
  (if (email->tokens-id crux-node email)
    (email->tokens crux-node email)
    (let [tokens (db/->tokens)]
      (crux/submit-tx crux-node [[:crux.tx/put tokens]])
      tokens)))

(comment

  ;; Demo of expiring documents
  (require '[tick.alpha.api :as t])

  (defn show-results [crux-node]
    (crux/q (crux/db crux-node) '{:find [?e]
                                  :where [[?e :crux.db/id ?i]]}))

  ;; Start a local crux node
  (def crux-node (crux/start-node {}))

  ;; no results
  (show-results crux-node)

  ;; Add a document
  (crux/submit-tx crux-node [[:crux.tx/put {:crux.db/id :foo
                                            :invite/user :bar}]])

  ;; We can see something now
  (show-results crux-node)

  ;; We can provide when doc starts being valid
  (crux/submit-tx crux-node [[:crux.tx/put {:crux.db/id :bar
                                            :invite/user :afu}
                              (t/inst (t/now))]])

  ;; See more things now
  (show-results crux-node)

  ;; We can say when it should stop being valid
  (crux/submit-tx crux-node [[:crux.tx/put {:crux.db/id :expiring
                                            :invite/user :foo}
                              (t/inst (t/now))
                              (t/inst (t/+ (t/now) (t/new-duration 5 :seconds)))]])

  ;; Disappears after 5 seconds now
  [(t/now)
   (show-results crux-node)]

  ;; We can make something valid in the future
  (crux/submit-tx crux-node [[:crux.tx/put {:crux.db/id :appearing
                                            :invite/user :afu}
                              (t/inst (t/+ (t/now) (t/new-duration 5 :seconds)))]])

  ;; Only appears once the document is considered valid
  [(t/now)
   (show-results crux-node)]

  (crux/submit-tx c [[:crux.tx/put {:crux.db/id :expiring-code
                                    :login/code (random-hex 32)}
                      (t/inst (t/now))
                      (t/inst (t/+ (t/now) (t/new-duration 5 :seconds)))]]))

(defn put-with-expiry [c e expiry]
  (crux/await-tx
    c
    (crux/submit-tx c [[:crux.tx/put
                        e
                        (t/inst (t/now))
                        (t/inst expiry)]])))

(defn email-code-handler [req]
  (let [email (-> req :route-params :email)
        crux-node (:crux req)
        return-to (or (:query-string req) nil)

        login-code (db/->login-code email)

        html-body (email-html-body-with-pw email (:login-code/code login-code) return-to)
        text-body (email-text-body-with-pw email (:login-code/code login-code) return-to)]
    (put-with-expiry crux-node
                     login-code
                     (t/+ (t/now) (t/new-duration 30 :minutes)))
    (send-email! email html-body text-body)
    {:status 201
     :body "Ok!"}))

(defn read-login-code [req]
  (let [email (-> req :route-params :email)
        provided-code (-> req :route-params :code)
        crux-node (:crux req)
        results (crux/q
                  (crux/db crux-node)
                  {:find '[p1]
                   :where '[[p1 :entity/type :code]
                            [p1 :code/email email]
                            [p1 :code/password code]]
                   :in [{'email email
                         'code provided-code}]})]
    (if (empty? results)
      {:status 403
       :body "Wrong email/code"}
      (let [code-id (-> results first first)]
        (crux/await-tx
          crux-node
          (crux/submit-tx crux-node
                          [[:crux.tx/delete code-id]]))
        (prn-str
          (create-tokens! crux-node email))))))

(def code-jwt-handler #'read-login-code)

(defn handle-trade-login-code! [req]
  (log :info req)
  (let [email (-> req :route-params :email)
        provided-code (-> req :route-params :code)
        crux-node (:crux req)
        res (db/trade-code-for-user!
              crux-node
              {:login-code/code provided-code
               :login-code/email email})]
    (println "we got this back")
    (pprint res)
    (if res
      {:status 200
       :body res}
      {:status 403})))


(defn me-handler [req]
  (let [user-id (-> req :identity :user-id)]
    {:status 200
     :body {:user (db/user-id->user (:crux req) user-id)
            :tokens (db/user-id->token (:crux req) user-id)}}))

(defn plugin-me-handler [req]
  (let [user-id (-> req :identity :user-id)]
    {:status 200
     :body (db/user-id->user (:crux req) user-id)}))

;; Helpers for other API funcs

;; TODO should do something more robust, probably want this to be a macro too
(defn check-ownership [user-id resource key-fn func]
  (if (nil? resource)
    {:status 404}
    (if (= user-id (key-fn resource))
      (func)
      {:status 403})))

(defn check-website-ownership [req func]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        website-id (-> req :route-params :website-id)
        current-website (db/website-id->website crux-node website-id)]
    (check-ownership user-id
                     current-website
                     :website/user-id
                     #(func current-website))))

(comment
  (db/website-id->website @instant-website.core/crux-node
                          "w4123")
  (db/domain-id->domain @instant-website.core/crux-node
                        "wsw"
                        "w4123"))

(defn check-domain-ownership [req func]
  (let [crux-node (:crux req)
        user-id (-> req :identity :user-id)
        domain-id (-> req :route-params :domain-id)
        current-domain (db/domain-id->domain crux-node domain-id)]
    (check-ownership user-id
                     current-domain
                     :domain/user-id
                     #(func current-domain))))

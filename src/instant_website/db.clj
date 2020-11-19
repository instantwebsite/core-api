(ns instant-website.db
  (:refer-clojure :exclude [find])
  (:require
    [clojure.pprint :refer [pprint] :rename {pprint pp}]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    ;;
    [crux.api :as crux]
    [tick.alpha.api :as t]
    [crypto.random :as crypto]
    ;;
    [instant-website.stripe :as stripe]))

;; Contains anything/everything that touches the DB, either retrieving or putting
;; TODO break this up somehow. Unsure exactly on how just yet, but we should

(defn nid [prefix]
  (str prefix (crypto/url-part 16)))

;; List of used Prefix IDs
;; (each crux.db/id is 16 random hex + a prefix letter)
;; This doesn't have any real impact on anything else but
;; easier to identify what is what when debugging
;;
;; - C => login-code
;; - T => token
;; - A => API token
;; - P => Figma plugin token
;; - U => user
;; - W => website
;; - D => domain
;; - V => verification code
;; - I => image
;; - E => vector

(defn ->login-code [email]
  {:crux.db/id (nid "c")
   :login-code/code (crypto/hex 32)
   :login-code/email email})

(defn ->tokens []
  {:crux.db/id (nid "t")
   :tokens/api (nid "a")
   :tokens/plugin (nid "f")})

;; :user/plan can be :pro or :free
(defn ->user [token login-code]
  {:crux.db/id (nid "u")
   :user/token-id (:crux.db/id token)
   :user/plan :free
   :user/stripe-id nil
   :user/email (:login-code/email login-code)})

(defn ->website [user]
  {:crux.db/id (nid "w")
   :website/user-id (:crux.db/id user)
   :website/permanent? false
   :website/startpage "startpage"
   :website/name "My Website"
   :website/description ""
   :website/pages {}
   :website/expired? false
   :website/created-at (t/inst (t/now))
   :website/updated-at (t/inst (t/now))})

(defn nonempty-string? [s]
  (and
    (string? s)
    (not (empty? s))))

(s/def :page/path nonempty-string?)
(s/def :page/title string?)
(s/def :page/json-content string?)
(s/def :page/html-content string?)

(s/def ::page
  (s/keys :req [:page/path
                :page/title
                :page/json-content
                :page/html-content]))

(defn ->page
  [path title json-content html-content fonts]
  {:pre [(s/valid? nonempty-string? path)
         (s/valid? string? title)
         (s/valid? string? json-content)
         (s/valid? string? html-content)]
   :post [(s/valid? ::page %)]}
  {:page/path path
   :page/title title
   :page/fonts fonts
   :page/created-at (t/inst (t/now))
   :page/updated-at (t/inst (t/now))
   :page/json-content json-content
   :page/html-content html-content})

;; Only a-zA-Z and one or more dots
(defn domain-like? [s]
  (if s
    (let [splitted (str/split s #"\.")]
      (if (= (count splitted) 1)
        false
        (every? #(re-matches #"[-a-z]+" %)
                splitted)))
    false))

(comment
  (domain-like? "instantwebsite.app")
  (domain-like? "asd!instantwebsite.app")
  (domain-like? "asd.instantwebsite.app")
  (domain-like? "klasndlkansd")
  (domain-like? "klasnd.lkansd")
  (domain-like? "klasnd.lkansd"))

(s/def :domain/hostname domain-like?)
;; (expound/defmsg :domain/hostname "Should be a hostname like 'mysite.com'")

(s/def ::domain
  (s/keys :req [:crux.db/id
                :domain/hostname]))
                ;; :domain/website-id
                ;; :domain/website-revision
                ;; :domain/verified?
                ;; :domain/verification-code
                ;; :domain/auto-update?
                ;; :domain/user-id]))

(defn ->domain [user]
  {:crux.db/id (nid "d")
   :domain/hostname nil
   :domain/website-id nil
   :domain/website-revision nil
   :domain/verified? false
   :domain/created-at (t/inst (t/now))
   :domain/updated-at (t/inst (t/now))
   :domain/verification-code (nid "v")
   :domain/auto-update? false
   :domain/user-id (:crux.db/id user)})

(defn ->image [user hash location]
  {:crux.db/id (nid "i")
   :image/hash hash
   :image/location location
   :image/user-id (:crux.db/id user)})

(defn ->vector [user website page location figma-id]
  {:crux.db/id (nid "e")
   :vector/location location
   :vector/figma-id figma-id
   :vector/website-id (:crux.db/id website)
   :vector/page-id (:crux.db/id page)
   :vector/user-id (:crux.db/id user)})

(defn put!
  "Submits crux.tx/put to provided crux node. Accepts vector
  of entities or just one entity"
  [crux-node entity]
  (if (vector? entity)
    (crux/submit-tx crux-node
                    (map (fn [ee]
                           [:crux.tx/put ee])
                         entity))
    (crux/submit-tx crux-node [[:crux.tx/put entity]])))

(defn await+put!
  "Same as db/put! with awaits confirmation that the transaction
  went through"
  [c e]
  (crux/await-tx
    c
    (put! c e)))

(defn put-with-end!
  "Same as db/put! but with end-time to say when entity should
  expire"
  [c e end-time]
  (if (vector? e)
    (crux/submit-tx c (map (fn [ee]
                             [:crux.tx/put
                              ee
                              (t/inst (t/now))
                              end-time])
                           e))
    (crux/submit-tx c [[:crux.tx/put
                        e
                        (t/inst (t/now))
                        end-time]])))

(defn shape->func [shape]
  (condp = shape
    :one (fn [r]
           (-> r first first))
    :vec (fn [r] (first r))
    :many (fn [r]
            (into []
                  (map first r)))
    :ident identity
    identity))

(defn pp-ret [e]
  (pp e)
  e)

(defn find
  "Calls crux/q with (crux/db c) and provided args.
  - f => find query vector
  - w => where query vector
  - shape => keyword of [:one, :vec, :many, :ident]. Defaults to :ident
  - args => map of args"
  ([c f w]
   (find c f w :ident))
  ([c f w shape]
   (find c f w shape {}))
  ([c f w shape args]
   (let [format-results (shape->func shape)]
     (format-results
       (crux/q (crux/db c)
               {:find f
                :where w
                :args [args]})))))

(defn delete! [c e]
  (crux/submit-tx c [[:crux.tx/delete e]]))

;; Have this function so we can introduce logging if needed
(defn mut [old new]
  (merge old new))

(defn id-mut! [c e-id new-e]
  (let [new-entity (merge {:crux.db/id e-id}
                          new-e)]
    (put! c new-entity)))

(defn api-token->user-id [c api-token]
  (find c
        '[?user]
        '[[?token :tokens/api $api-token]
          [?user :user/token-id ?token]]
        :one
        {'$api-token api-token}))

(defn plugin-token->user-id [c plugin-token]
  (find c
        '[?user]
        '[[?token :tokens/plugin $plugin-token]
          [?user :user/token-id ?token]]
        :one
        {'$plugin-token plugin-token}))

;; Profile is user + tokens
(defn api-token->profile [c api-token]
  (let [res (find c
                  '[?ue ?email ?stripe-id ?plan ?api-token ?plugin-token]
                  '[[?te :tokens/api $api-token]
                    [?ue :user/token-id ?te]
                    [?ue :user/email ?email]
                    [?ue :user/plan ?plan]
                    [?ue :user/stripe-id ?stripe-id]
                    [?te :tokens/api ?api-token]
                    [?te :tokens/plugin ?plugin-token]]
                  :vec
                  {'$api-token api-token})
        [user-id email stripe-id plan api-token plugin-token] res]
    {:crux.db/id user-id
     :user/email email
     :user/stripe-id stripe-id
     :user/plan plan
     :tokens/api api-token
     :tokens/plugin plugin-token}))

(defn user-id->profile [c user-id]
  (let [res (find c
                  '[?ue ?email ?stripe-id ?plan ?api-token ?plugin-token]
                  ;; '[?ue]
                  '[[?ue :crux.db/id $user-id]
                    [?ue :user/token-id ?te]
                    [?te :tokens/api ?api-token]
                    [?ue :user/email ?email]
                    [?ue :user/plan ?plan]
                    [?ue :user/stripe-id ?stripe-id]
                    [?te :tokens/api ?api-token]
                    [?te :tokens/plugin ?plugin-token]]
                  :vec
                  {'$user-id user-id})
        [user-id email stripe-id plan api-token plugin-token] res]
    {:crux.db/id user-id
     :user/email email
     :user/stripe-id stripe-id
     :user/plan plan
     :tokens/api api-token
     :tokens/plugin plugin-token}))

  ;; (user-id->profile crux-node "c5bc637f3fc0726e95dedebc37dd44e81"))

(defn login-code->user-id [c login-code]
  (find c
        ['u]
        [['e :login-code/code (:login-code/code login-code)]
         ['e :login-code/email (:login-code/email login-code)]
         ['u :user/email (:login-code/email login-code)]]
        :one))

(defn login-code-has-user? [c login-code]
  (boolean (login-code->user-id c login-code)))

(defn login-code-valid? [crux-node login-code]
  (boolean
    (find crux-node
          ['e]
          [['e :login-code/code (:login-code/code login-code)]
           ['e :login-code/email (:login-code/email login-code)]]
          :one)))

(defn find-login-code [crux-node login-code]
  (find crux-node
        '[(eql/project ?e [:crux.db/id
                           :login-code/code
                           :login-code/email])]
        '[[?e :login-code/code ?code]
          [?e :login-code/email ?email]]
        :one
        {'?code (:login-code/code login-code)
         '?email (:login-code/email login-code)}))

(defn user-id->user [c user-id]
  (find c
        '[(eql/project ?user [*])]
        '[[?user :crux.db/id ?user-id]]
        :one
        {'?user-id user-id}))

;; TODO should only be able to be called with the right user
(defn domain-id->domain [c user-id domain-id]
  (find c
        '[(eql/project ?domain [*])]
        '[[?domain :crux.db/id ?domain-id]
          [?domain :domain/user-id ?user-id]]
        :one
        {'?user-id user-id
         '?domain-id domain-id}))

(defn user-id->domains [c user-id]
  (find c
        '[(eql/project ?domain [*])]
        '[[?domain :domain/user-id ?user-id]]
        :many
        {'?user-id user-id}))

;; TODO should only be able to be called with the right user
(defn remove-content-from-website-pages [website]
  (assoc website
         :website/pages
         (reduce (fn [acc [k v]]
                   (assoc acc k (dissoc v :page/json-content :page/html-content)))
                 {}
                 (:website/pages website))))

;; TODO should only be able to be called with the right user
(defn remove-page-content-from-websites [websites]
  (map remove-content-from-website-pages
       websites))

(defn user-id->websites [c user-id]
  (map
    (fn [website]
      ;; TODO don't particularly like the "first first second" thing here
      ;; but dealing with what we get back from doing joins with eql/project
      ;; which is a list of matches, and domain is a list of vectors.
      ;; So 1st "first" is the first domain that matches
      ;; 2nd "first" is the first value "[:domain/hostname "my value"]"
      ;; and the final "second" is taking the value of the :domain/hostname attr
      ;; TODO rename feature in the works:
      ;; https://github.com/juxt/crux/pull/1193
      (let [domain (-> website :domain/_website-id first first second)]
        (-> website
            (assoc :website/domain domain)
            (dissoc :domain/_website-id))))
    (remove-page-content-from-websites
      (find c
            '[(eql/project ?website [* {:domain/_website-id [:domain/hostname]}])]
            '[[?website :website/user-id ?user-id]]
            :many
            {'?user-id user-id}))))

(defn website-id->pages [c website-id]
  (find c
        '[(eql/project ?page [*])]
        '[[?page :page/website-id ?website-id]]
        :many
        {'?website-id website-id}))


(defn website-id->website [c website-id]
  (let [website (find c
                      '[(eql/project ?website [* {:domain/_website-id [*]}])]
                      '[[?website :crux.db/id ?website-id]]
                      :one
                      {'?website-id website-id})
        domain (-> website :domain/_website-id first)]
    (when-not (nil? website)
      (-> website
          (assoc :website/domain domain)
          (dissoc :domain/_website-id)))))

(defn website-id->website-without-pages [c website-id]
  (let [website (find c
                      '[(eql/project ?website [* {:domain/_website-id [*]}])]
                      '[[?website :crux.db/id ?website-id]]
                      :one
                      {'?website-id website-id})
        domain (-> website :domain/_website-id first)]
      (-> website
          (assoc :website/domain domain)
          (dissoc :domain/_website-id)
          (remove-content-from-website-pages))))

(defn website-id+page-name->page [c website-id page-name]
  (find c
        '[(eql/project ?page [*])]
        '[[?website :crux.db/id ?website-id]
          [?page :page/website-id ?website-id]
          [?page :page/title ?page-name]]
        :one
        {'?website-id website-id
         '?page-name page-name}))

(defn find-vector [c website-id vector-id]
  (find c
        '[(eql/project ?vector [*])]
        '[[?vector :vector/figma-id ?vector-id]
          [?vector :vector/website-id ?website-id]]
        :one
        {'?website-id website-id
         '?vector-id vector-id}))


(defn customer-id->user [c customer-id]
  (find c
        '[(eql/project ?user [*])]
        '[[?user :user/stripe-id ?customer-id]]
        :one
        {'?customer-id customer-id}))

(defn user-id->token [c user-id]
  (find c
        '[(eql/project ?token [*])]
        '[[?user :crux.db/id ?user-id]
          [?user :user/token-id ?token-id]
          [?token :crux.db/id ?token-id]]
        :one
        {'?user-id user-id}))

(defn all-tokens [c]
  (find c
        '[(eql/project ?token [*])]
        '[[?token :login-code/code ?code]
          [?token :login-code/email ?email]]
        :many))

(defn login-code->profile [crux-node login-code]
  (let [user-id (login-code->user-id crux-node login-code)]
    (user-id->profile crux-node user-id)))

(defn create-new-user! [crux-node login-code]
  (let [token (->tokens)
        user (->user token login-code)
        user-id (:crux.db/id user)
        email (:login-code/email login-code)
        customer (stripe/create-customer! user-id email)
        new-user (mut user {:user/stripe-id (:id customer)})]
    (crux/await-tx crux-node (put! crux-node [token new-user]))
    (user-id->profile crux-node user-id)))

(defn create-user-if-needed [c code-matched? user-found? login-code]
  (condp = [code-matched? user-found?]
    [true true] (login-code->profile c login-code)
    [true false] (create-new-user! c login-code)
    [false false] nil
    [false true] nil))

(defn trade-code-for-user! [c login-code]
  (let [code-matched? (login-code-valid? c login-code)
        user-found? (login-code-has-user? c login-code)
        res (create-user-if-needed c code-matched?  user-found?  login-code)]
    (when res
      (crux/await-tx c (delete! c (:crux.db/id
                                    (find-login-code c login-code))))
      res)))

(defn latest-content-hash
  "Gets the latest :crux.db/content-hash for a provided :crux.db/id"
  [crux-node id]
  (-> (crux/entity-history
        (crux/db crux-node)
        id
        :desc)
      first
      :crux.db/content-hash
      str))

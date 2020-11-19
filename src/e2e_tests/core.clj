(ns e2e-tests.core
  (:require 
    [clojure.pprint :refer [pprint]]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    ;;
    [etaoin.api :as etaoin]
    [clj-http.client :as http]
    [hiccup.core :as hiccup]
    [org.httpkit.server :as httpkit]
    [cheshire.core :refer [parse-string generate-string]]
    [me.raynes.conch.low-level :as sh]
    ;; 
    [instant-website.db :as db]
    [instant-website.core :as core])
  (:import [java.io File]
           [java.util Base64]))


;; A lot of of the code in this file is from this source:
;; https://github.com/oliyh/kamera/blob/master/src/clj/kamera/core.clj
;; Distributed under Eclipse Public License

(defonce test-results (atom {}))
(defonce server-instance (atom nil))
(defonce user (atom nil))
(defonce browser (atom nil))

;; Each item in this list corresponds to a file in `e2e/json-payloads/:name.json`
(def active-tests ["font-sizes"
                   "full-website"
                   "mixed-font-size"
                   "auto-layout"])

(defn magick [operation
              operation-args
              {:keys [imagemagick-options]}]
  (let [{:keys [path timeout]} imagemagick-options
        executable (or (when path
                         (let [f (io/file path)]
                           (if (.isDirectory f)
                             [(.getAbsolutePath (io/file f operation))]
                             [(.getAbsolutePath f) operation])))
                       [operation])
        args (into executable operation-args)
        process (apply sh/proc args)]
    (println args)
    {:stdout (sh/stream-to-string process :out)
     :stderr (sh/stream-to-string process :err)
     :exit-code (sh/exit-code process timeout)}))

(defn ^File append-suffix
  ([^File file suffix] (append-suffix (.getParent file) file suffix))
  ([dir ^File file suffix]
   (let [file-name (.getName file)]
     (io/file dir (string/replace file-name #"\.(\w+)$" (str suffix ".$1"))))))

(defn trim-images
  ([expected actual opts] (trim-images expected actual opts 1))
  ([^File expected ^File actual opts fuzz-percent]
   (mapv (fn [^File file]
           (let [trimmed (append-suffix file ".trimmed")]
             (magick "convert"
                     [(.getAbsolutePath file)
                      "-trim"
                      "+repage"
                      "-fuzz" (str fuzz-percent "%")
                      (.getAbsolutePath trimmed)]
                     opts)
             trimmed))
         [expected actual])))

(defn dimensions [^File image opts]
  (let [{:keys [stdout exit-code]} (magick "convert"
                                           [(.getAbsolutePath image)
                                            "-ping"
                                            "-format"
                                            "%w:%h"
                                            "info:"]
                                           opts)]
    (when (zero? exit-code)
      (mapv #(Long/parseLong (string/trim %)) (string/split stdout #":")))))

(defn crop-images
  ([expected actual opts] (crop-images expected actual opts "+0+0"))
  ([^File expected ^File actual opts crop-anchor]
   (let [expected-dimensions (dimensions expected opts)
         actual-dimensions (dimensions actual opts)
         [target-width target-height] [(apply min (map first [expected-dimensions actual-dimensions]))
                                       (apply min (map second [expected-dimensions actual-dimensions]))]]
     (mapv (fn [[^File file [width height]]]
             (if (or (< target-width width)
                     (< target-height height))
               (let [cropped (append-suffix file ".cropped")]
                 (magick "convert"
                         [(.getAbsolutePath file)
                          "-crop"
                          (format "%sx%s%s" target-width target-height crop-anchor)
                          (.getAbsolutePath cropped)]
                         opts)
                 cropped)
               file))
           [[expected expected-dimensions]
            [actual actual-dimensions]]))))

(def default-opts
  {:default-target      {;; :url must be supplied on each target
                         ;; :reference-file must be supplied on each target
                         :metric               "mae" ;; see https://imagemagick.org/script/command-line-options.php#metric
                         :metric-threshold     0.01
                         :reference-directory  "e2e/references"
                         :screenshot-directory "e2e/screenshots"
                         :normalisations       [:trim :crop]
                         :ready?               nil ;; (fn [session] ... ) a predicate that should return true when ready to take the screenshot
                                                   ;; see element-exists?
                         :assert?              true ;; runs a clojure.test assert on the expected/actual when true, makes no assertions when false
                         :resize-to-contents   {:height? true
                                                :width? false}}
   :normalisation-fns   {:trim trim-images
                         :crop crop-images}
   :imagemagick-options {:path nil      ;; directory where binaries reside on linux, or executable on windows
                         :timeout 2000} ;; kill imagemagick calls that exceed this time, in ms
   ;; suggest you fix the width/height to make it device independant
   :report              {:enabled? true}}) ;; write a report after testing

(defn- normalisation-chain [^File expected ^File actual]
  [{:normalisation :original
    :expected expected
    :actual actual}])

(defn- normalise-images [normalisations ^File expected ^File actual {:keys [normalisation-fns] :as opts}]
  (let [expected-dimensions (dimensions expected opts)
        actual-dimensions (dimensions actual opts)
        chain (normalisation-chain expected actual)]
    (if (and expected-dimensions actual-dimensions
             (not= expected-dimensions actual-dimensions))
      (reduce (fn [acc norm-key]
                (let [{:keys [expected actual]} (last acc)
                      norm-fn (get normalisation-fns norm-key)
                      [e' a'] (norm-fn expected actual opts)]
                  (conj acc {:normalisation norm-key
                             :expected e'
                             :actual a'})))
              chain
              normalisations)
      chain)))

(defn ->absolute-paths [normalisation-chain]
  (map (fn [n]
         (-> n
             (update :expected #(.getAbsolutePath %))
             (update :actual #(.getAbsolutePath %))))
       normalisation-chain))

(defn compare-images [^File expected
                      ^File actual
                      {:keys [metric screenshot-directory normalisations]}
                      opts]
  (if-not (and (.exists expected) (.exists actual))
    {:metric 1
     :expected (.getAbsolutePath expected)
     :actual (.getAbsolutePath actual)
     :normalisation-chain (->absolute-paths (normalisation-chain expected actual))
     :errors (->> [(when-not (.exists expected) (format "Expected is missing: %s" (.getAbsolutePath expected)))
                   (when-not (.exists actual) (format "Actual is missing: %s" (.getAbsolutePath actual)))
                   (remove nil?)
                   (into [])])}

    (merge
     {:metric 1
      :expected (.getAbsolutePath expected)
      :actual (.getAbsolutePath actual)}
     (let [difference (append-suffix screenshot-directory expected ".difference")
           normalisation-chain (try (normalise-images normalisations expected actual opts)
                                    (catch Throwable t
                                      (log/warn "Error normalising images" t)
                                      (normalisation-chain expected actual)))
           {:keys [^File expected ^File actual]} (last normalisation-chain)
           {:keys [stdout stderr exit-code]}
           (magick "compare"
                   ["-verbose" "-metric" metric "-compose" "src"
                    (.getAbsolutePath expected)
                    (.getAbsolutePath actual)
                    (.getAbsolutePath difference)]
                   opts)
           mean-absolute-error (when-let [e (last (re-find #"all: .* \((.*)\)" stderr))]
                                 (read-string e))]

       (merge-with concat
                   {:actual (.getAbsolutePath actual)
                    :expected (.getAbsolutePath expected)
                    :normalisation-chain (->absolute-paths normalisation-chain)}

                   (if (not= 2 exit-code)
                     {:difference (.getAbsolutePath difference)}
                     {:errors [(format "Error comparing images - ImageMagick exited with code %s \n stdout: %s \n stderr: %s"
                                       exit-code stdout stderr)]})

                   (if mean-absolute-error
                     {:metric mean-absolute-error}
                     {:errors [(format "Could not parse ImageMagick output\n stdout: %s \n stderr: %s"
                                       stdout stderr)]}))))))

(defn create-website [token file]
  (->
    (http/post "http://localhost:8080/plugin-api/websites"
               {:headers {"Authorization" (str "Token " token)}
                :body (File. (str "e2e/json-payloads/" file ".json"))})
    :body
    (parse-string true)
    :crux.db/id))

(defn update-website [token file website-id]
  (->
    (http/put (str "http://localhost:8080/plugin-api/websites/" website-id)
              {:headers {"Authorization" (str "Token " token)}
               :body (File. (str "e2e/json-payloads/" file ".json"))})
    :body
    (parse-string true)))

;; Run this function to check that it still renders correctly
(defn test-render-of-payload [token file browser]
  (let [website-id (create-website token file)
        website    (update-website token file website-id)
        url        (str "http://localhost:8888/" website-id)
        actual-p   (format "e2e/actual/%s.png" file)
        expected-p (format "e2e/expected/%s.png" file)]
    (etaoin/go browser url)
    (etaoin/wait browser 1)
    (etaoin/screenshot browser actual-p)
    (merge
      (compare-images
        (File. expected-p)
        (File. actual-p)
        (:default-target default-opts)
        default-opts)
      {:website-id website-id
       :website-url url})))

;; Run this function to create initial snapshot
(defn create-expected [token file browser]
  (let [website-id (create-website token file)
        website    (update-website token file website-id)
        url        (str "http://localhost:8888/" website-id)
        expected-p (format "e2e/expected/%s.png" file)]
    (etaoin/go browser url)
    (etaoin/wait browser 1)
    (etaoin/screenshot browser expected-p)))

(defn create-user []
  (let [login-code (db/->login-code "test@example.com")
        token (db/->tokens)
        new-user (db/->user token login-code)]
    (doseq [t [token new-user]]
      (db/put! @core/crux-node t))
    (reset! user {:user new-user
                  :token token})))

(comment
  (db/->login-code "test@example.com")
  (identity @core/crux-node)
  (create-user))

(defn plugin-token []
  (-> @user :token :tokens/plugin))

(defn api-token []
  (-> @user :token :tokens/plugin))

(defn create-browser []
  (reset! browser (etaoin/chrome {:headless true
                                  :size [1920 1080]})))

(defn run-test-file [n]
  (swap! test-results
         assoc
         n
         (test-render-of-payload (plugin-token) n @browser)))

(defn run-all-tests []
  (doseq [test-name active-tests]
    (run-test-file test-name))
  (println "All tests executed!")
  true)

(comment
  (run-all-tests))

(defn image-view [k n]
  [:div
    [:h3 n]
    [:label
     {:for (str k "-" n)}
     "Show"]
    [:input
     {:id (str k "-" n)
      :class "toggle"
      :type "checkbox"}]
    [:img
     {:src (str n "/" k)}]])

(defn render-results []
  [:div
   (map (fn [[k v]]
          [:div
            {:style {:border-top "5px solid grey"}}
            [:h1 k]
            [:form
              {:action (str "/run-tests/" k)
               :method "post"}
              [:button
               (str "Run " k " tests")]]
            [:h2 (str "Difference score: " (:metric v))]
            [:div
             [:a
              {:href (:website-url v)
               :target "_blank"}
              (:website-url v)]]
            (image-view k "difference")
            [:form
              {:action (str "/accept-diff/" k)
               :method "post"}
              [:button
               "Confirm difference"]]
            (image-view k "actual")
            (image-view k "expected")])
        @test-results)])

(def style "
img {
  display: none;
}
.toggle:checked ~ img {
             display: block;
}
")

(defn app [req]
  (try
    (let [uri (:uri req)
          splitted (string/split uri #"/")]
        (if (> (count splitted) 0)
          (let [part (nth splitted 1)
                testname (nth splitted 2)]
            (condp = part
              "expected" {:body (io/file (str "e2e/expected/" testname ".png"))}
              "actual" {:body (io/file (str "e2e/actual/" testname ".png"))}
              "difference" {:body (io/file (str "e2e/screenshots/" testname ".difference.png"))}
              "accept-diff" (do
                              (io/copy (io/file (str "e2e/actual/" testname ".png"))
                                       (io/file (str "e2e/expected/" testname ".png")))
                              {:headers {"Location" "/"}
                               :status 307
                               :body "ok"})
              "run-tests" (do
                            (swap! test-results
                                   assoc
                                   testname
                                   (test-render-of-payload (plugin-token) testname @browser))
                            {:headers {"Location" "/"}
                             :status 307
                             :body "ok"})))
          {:status 200
           :body
            (hiccup/html [:html
                          [:head
                            [:title "Test results"]
                            [:style style]]
                          [:body
                           (render-results)]])}))
    (catch Exception err
      {:body (str err)})))

(defn start-server []
  (when (nil? @core/crux-node)
    (throw (Exception. "@core/crux-node nil, make sure you run core-api server before starting E2E server")))
  (create-user)
  (create-browser)
  (println "Starting E2E test server, listening on localhost:8378")
  (reset! server-instance (httpkit/run-server
                            #'app
                            {:port 8378})))

(comment
  (create-user)
  (start-server)
  (create-expected (plugin-token) "auto-layout" @browser)
  ;; (create-expected plugin-token "basic" browser)
  ;; (create-expected plugin-token "four-corners" browser)
  (create-expected plugin-token "flexible-corners" browser)
  (create-expected plugin-token "alignment" browser)
  (swap! test-results
         assoc
         "alignment"
         (test-render-of-payload plugin-token "alignment" browser))
  ;; Create new user
  (require '[dev])

  ;; (def plugin-token "f023261dc42dda92dec73c5a6dc6332d1")
  ;; (def test-files ["full-website"])
  ;; (def test-file (first test-files))

  (etaoin/quit browser)
  ;; (test-render-of-payload plugin-token test-file browser)
  ;; :metric should be 0

  ;; (db/find @instant-website.core/crux-node
  ;;          '[(eql/project ?token [*])]
  ;;          '[[?token :token/plugin ?plugin-token]]
  ;;          :one)



  #_(create-expected plugin-token "auto-layout" browser)
  (swap! test-results
         assoc
         "auto-layout"
         (test-render-of-payload (plugin-token) "auto-layout" @browser))
  (swap! test-results
         assoc
         "font-sizes"
         (test-render-of-payload (plugin-token) "font-sizes" @browser)))
  ;; :metric should be 0

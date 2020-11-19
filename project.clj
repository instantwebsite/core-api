(defproject instant-website "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :plugins [[lein-ring "0.12.5"]
            [cider/cider-nrepl "0.25.4"]
            [lein-cloverage "1.1.2"]]
  :ring {:handler instant-website.core/app
         :init instant-website.core/init!
         :nrepl {:start? true
                 :port 9998}}
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.clojure/core.cache "1.0.207"]
                 [org.clojure/core.memoize "1.0.236"]
                 [http-kit "2.5.0-RC1"]
                 [cheshire "5.10.0"]
                 [hiccup "2.0.0-alpha2"]
                 [reagent "1.0.0-alpha2"]
                 [digest "1.4.9"]
                 [compojure "1.6.2"]
                 [me.raynes/conch "0.8.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [jumblerg/ring-cors "2.0.0"]
                 [edn-query-language/eql "1.0.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.29"]
                 [ch.qos.logback/logback-classic "1.1.3"]
                 [crypto-random "1.2.0"]
                 [clj-time "0.15.2"]
                 [clj-http "3.10.3"]
                 [buddy/buddy-auth "2.2.0"]
                 [toyokumo/tarayo "0.2.2"]
                 [metosin/muuntaja "0.6.7"]
                 [juxt/crux-core "20.09-1.12.1-beta"]
                 [juxt/crux-rocksdb "20.09-1.12.1-beta"]
                 [juxt/crux-metrics "20.09-1.12.1-alpha"]
                 [org.dhatim/dropwizard-prometheus "2.2.0"]
                 [io.prometheus/simpleclient_dropwizard "0.8.1"]
                 [io.prometheus/simpleclient_hotspot "0.8.1"]
                 [byte-streams "0.2.4"]
                 [nrepl "0.8.2"]
                 [tick "0.4.26-alpha"]
                 [jarohen/chime "0.3.2"]
                 [paraman "0.1.2"]
                 [etaoin "0.3.10"]
                 [expound "0.8.5"]
                 [clj-commons/iapetos "0.1.11"]
                 [io.prometheus/simpleclient_hotspot "0.9.0"]
                 [cawdy "0.3.1"]
                 [rocks.clj/z "0.1.0"]]
  :main ^:skip-aot instant-website.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})

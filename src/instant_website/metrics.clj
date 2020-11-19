(ns instant-website.metrics
  (:require
    [iapetos.core :as prometheus]
    [iapetos.collector.jvm :as jvm]
    [iapetos.collector.ring :as ring]
    [iapetos.collector.fn :as fn]))

(defonce registry
  (-> (prometheus/collector-registry)
      (jvm/initialize)
      (ring/initialize)
      (fn/initialize)))

(defn wrap [handler]
  (ring/wrap-metrics handler registry {:path "/metrics"}))

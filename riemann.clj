; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

(require '[clojure.string :as string])

(include "inc/helpers.clj")
(include "inc/thresholds.clj")

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server  {:host host})
  (sse-server {:host host})

  (graphite-server :host host
                   :port 2004
                   :parser-fn
                   (fn [{:keys [service] :as event}]
                     (if-let [[source hostname checktype servicename checkname perfdata metricname] (string/split service #"\." 7)]
                      (if-let [[servicetype realservicename] (clojure.string/split servicename #"_" 2)]
                        {
                          :host (string/replace hostname #"_" ".")
                          :service (string/join "." ["icinga" "riemann" servicetype realservicename (string/replace (string/replace metricname #"load_1_min|load_15_min|load_5_min" {"load_1_min" "load1" "load_15_min" "load15" "load_5_min" "load5"}) #"\.value" "")])
                          :metric (:metric event)
                          :tags source
                          :checktype checktype
                          :state "ok"
                          :servicetype servicetype
                          :time (:time event)
                          :ttl 60}))))
  (graphite-server :host host
                   :parser-fn
                   (fn [{:keys [service] :as event}]
                     (if-let [[hostname metricname] (string/split service #"\." 2)]
                        {
                          :host "at-vie-fn01"
                          :service (string/join "." ["freenas" (string/replace metricname #"\.value" "")])
                          :metric (:metric event)
                          :tags ["FreeNAS"]
                          :state "ok"
                          :time (:time event)
                          :ttl 60})))
  (def graph
    (opentsdb {:host host}))
  (def logstash-forward
    (logstash {:host host
               :port 25827
               :pool-size 4
               :protocol :tcp
               :claim-timeout 0.1
               :reconnect-interval 4 })))

;(periodically-expire 30 {:keep-keys [:host :service :tags, :state, :description, :metric]})
(let [index (index)]
  (streams
    (default :ttl 60
      index
      (where (tags "icinga2")
             ;#(info %)
             graph)
      (where (tagged-any "FreeNAS")
             ;#(info %)
             graph)
      (where (tagged-any "collectd")
             graph)
      logstash-forward
      ;#(info %)
      (expired
        (fn [event] (info "EXPIRED" event))))))
      ;#(info %))))
      ;(changed-state {:init "ok"}
      ;  (stable 5 :state
      ;    (fn [event] (info "CHANGE IN STATE DETECTED" event)))))))

; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server  {:host host})
  (sse-server {:host host})
  (graphite-server {:host host})
  ;(graphite-server :host host
  ;                 :parser-fn
  ;                 (fn [{:keys [service] :as event}]
  ;                   (if-let [[source hostname checktype servicename checkname perfdata metricname] (clojure.string/split service #"\." 7)]
  ;                    (if-let [[servicetype realservicename] (clojure.string/split servicename #"_" 2)]
  ;                      {
  ;                        :host (clojure.string/replace hostname #"_" ".")
  ;                        :service (clojure.string/join "." [servicetype realservicename metricname])
  ;                        :metric (:metric event)
  ;                        :tags source
  ;                        :checktype checktype
  ;                        :state "ok"
  ;                        :servicetype servicetype
  ;                        :time (:time event)
  ;                        :ttl 30}))))
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
    (default :ttl 30
      index
      ;(where (tags "icinga2")
      ;       graph)
      (where (tagged-any "collectd")
             graph)
      (where (host nil)
             graph)
      logstash-forward
      ;#(info %)
      (expired
        (fn [event] (info "EXPIRED" event))))))
      ;#(info %))))
      ;(changed-state {:init "ok"}
      ;  (stable 5 :state
      ;    (fn [event] (info "CHANGE IN STATE DETECTED" event)))))))

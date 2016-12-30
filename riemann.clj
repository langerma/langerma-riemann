; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server  {:host host})
  (graphite-server :host host
                   :parser-fn
                   (fn [{:keys [service] :as event}]
                     (if-let [[source hostname checktype servicename checkname perfdata metricname] (clojure.string/split service #"\." 7)]
                       (if-let [[servicetype realservicename] (clojure.string/split servicename #"_" 2)]
                        {
                          :host (clojure.string/replace hostname #"_" ".")
                          :service (clojure.string/join "." [servicetype realservicename metricname])
                          :metric (:metric event)
                          :tags source
                          :checktype checktype
                          :servicetype servicetype
                          :time (:time event)
                          :ttl 30}))))
  (def graph
    (opentsdb {:host host})
  )
)

; Expire old events from the index every 5 seconds.
(periodically-expire 5 {:keep-keys [:host :service :tags, :state, :description, :metric]})
(let [index (index)]
  ; Inbound events will be passed to these streams:
  (streams
    (default :ttl 10
      index
      (where (tags "icinga2")
                 graph)
;      #(info "host:" (:host %) (:service %) "STATUS:" (:state %) "METRIC:" (:metric %)))))
;      ; Log expired events.
      (expired
        (fn [event] (info "expired" event))))))

;(periodically-expire 10 {:keep-keys [:host :service :tags, :state, :description, :metric]})
;(let [index (index)]
;  (streams
;    (default :ttl 60
;    index
;    #(info %))))

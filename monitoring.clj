; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

(require '[clojure.string :as string])

(include "include/helpers.clj")

(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server  {:host host})

  (graphite-server :host host
                   :port 2004
                   :parser-fn graphite-event-parser-icinga)

  (graphite-server :host host
                   :parser-fn graphite-event-parser-freenas)

  (def graph
    (opentsdb {:host host}))

  (def logit
    (logstash {:host host
               :port 25827
               :pool-size 4
               :protocol :tcp
               :claim-timeout 0.1
               :reconnect-interval 4 })))



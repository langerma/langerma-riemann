; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

(require '[clojure.string :as string])

(defn find-specific-threshold
  [{:keys [host tags]}
   {:keys [match-host match-tag match-default] :as threshold}]
  (cond
   match-tag     (and ((set tags) match-tag) threshold)
   match-host    (and (= match-host host) threshold)
   match-default threshold))

(defn match-threshold
  [{:keys [service]} [pattern payload]]
  (when (re-matches pattern service)
    payload))

(defn find-threshold
  [thresholds re-patterns event]
  (if-let [thresholds (or (get thresholds (:service event))
                          (some (partial match-threshold event) re-patterns))]
    (if (sequential? thresholds)
      (some (partial find-specific-threshold event) thresholds)
      thresholds)))

(defn threshold-check
  "Given a list of standard or inverted thresholds, yield
   a function that will adapt an inputs state.
   The output function does not process events with no metrics"
  [thresholds]
  (let [re-patterns (filter (complement (comp string? key)) thresholds)]
    (fn [{:keys [metric tags] :as event}]
      (try
        (if-let [{:keys [warning critical invert exact add-tags]}
                 (if metric (find-threshold thresholds re-patterns event))]
          (assoc event
            :tags (clojure.set/union (set tags) (set add-tags))
            :state
            (cond
             (nil? metric)                       "unknown"
             (and exact (not= (double metric) (double exact))) "critical"
             (and exact (= (double metric) (double exact)))    "ok"
             (and critical ((if invert <= >) metric critical)) "critical"
             (and warning ((if invert <= >) metric warning))  "warning"
             :else                              "ok"))
          event)
        (catch Exception e
          (error e "threshold-check failed for " event))))))

(include "inc/helpers.clj")
(include "inc/thresholds.clj")

(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server  {:host host})
  (sse-server {:host host})

  (graphite-server :host host
                   :port 2004
                   :parser-fn graphite-event-parser-icinga)
  (graphite-server :host host
                   :parser-fn graphite-event-parser-freenas)
  (def graph
    (opentsdb {:host host}))
  (def logstash-forward
    (logstash {:host host
               :port 25827
               :pool-size 4
               :protocol :tcp
               :claim-timeout 0.1
               :reconnect-interval 4 })))

(def default-ttl 30)
(periodically-expire 1)

(let [index (smap (threshold-check thresholds)
                  (tap :index (index)))
      alert (logstash {:host "127.0.0.1"
                       :port 25827
                       :protocol :tcp})]
  (streams
   (default :ttl default-ttl
     (let [memory-and-load-summary
           (where (or (service #"^icinga.riemann.UNIX.load")
                      (service #"^icinga.riemann.UNIX.memory"))
                  index
                  (by :service
                      (coalesce
                       (smap folds/sum
                             (with-but-icinga {:tags ["summary"]
                                               :ttl default-ttl
                                               :state "ok"}
                               index)))))
           total-network-traffic
           (where (service #"^interface-.*/if_octets/[tr]x$")
                  index
                  (coalesce
                   (smap folds/sum
                         (with-but-collectd {:service "total network traffic"
                                             :tags ["summary"]
                                             :ttl default-ttl
                                             :state "ok"}
                           index))))
           distinct-hosts
           (where (not (tagged "summary"))
                  (with :service "distinct hosts"
                        (coalesce
                         (smap folds/count
                               (with-but-collectd {:tags ["summary"]
                                                   :ttl default-ttl
                                                   :state nil}
                                 reinject)))))
           per-host-summaries
           (by [:host]
               (project [(service "icinga.riemann.UNIX.memory.USED")
                         (service "icinga.riemann.UNIX.memory.FBSD_MEM")]
                        (smap folds/quotient
                              (with {:service "icinga.riemann.UNIX.memory.quotient"
                                     :ttl default-ttl
                                     :tags ["summary"]}
                                (float-to-percent index)))))
           ;per-host-summaries
           ;(by [:host]
           ;    (project [(service "cpu-average/cpu-system")
           ;              (service "cpu-average/cpu-user")]
           ;             (smap folds/sum
           ;                   (with {:service "cpu-average/cpu-used"
           ;                          :ttl default-ttl}
           ;                         index)))
           ;    (project [(service "memory/memory-used")
           ;              (service "memory/memory-free")
           ;              (service "memory/memory-cached")
           ;              (service "memory/memory-buffered")]
           ;             (smap folds/sum
           ;                   (with {:service "memory/memory-total"
           ;                          :ttl default-ttl
           ;                          :tags ["summary"]}
           ;                         reinject)))
           ;    (project [(service "memory/memory-used")
           ;              (service "memory/memory-total")]
           ;             (smap folds/quotient
           ;                   (with {:service "memory/percent-used"
           ;                          :ttl default-ttl}
           ;                         (float-to-percent index)))))
           clock-skew
           (where (not (nil? host))
                  (clock-skew
                   (with-but-icinga {:service "clock skew"
                                       :tags ["internal"]}
                     (rate 5 index))))]
       (where (not (state "expired"))
              memory-and-load-summary
              total-network-traffic
              distinct-hosts
              per-host-summaries
              clock-skew
              alert
              graph
              index))
     (expired #(info "Expired" %)))))

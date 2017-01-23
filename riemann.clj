; -*- mode: clojure; -*-
; vim: filetype=clojure

(logging/init {:file "/var/log/riemann/riemann.log"})

(require '[clojure.string :as string]
         '[riemann.query :as query])

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

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
                          :service (string/join "." ["icinga" "riemann" servicetype realservicename (string/replace
                                                                                                      (string/replace
                                                                                                        (string/replace metricname #"load_1_min|load_15_min|load_5_min" {"load_1_min" "load1" "load_15_min" "load15" "load_5_min" "load5"}) #"\.value" "") #"check_snmp\." "")])
                          :metric (:metric event)
                          :tags source
                          :checktype checktype
                          ;:state nil
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
                          ;:state nil
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

(defn parse-stream
  [& children]
  (fn [e] (let [new-event (assoc e
                                 :host (str (:host e))
                                 :resource (:host e)
                                 :type "RiemannAlert")]
            (call-rescue new-event children))))

(defn log-info
  [e]
  #(info e))

; reap expired events every 10 seconds
(periodically-expire 60 {:keep-keys [:host :service :environment :resource :grid :cluster :ip :tags :metric :index-time]})

; set of severity functions
(defn severity
  [severity message & children]
  (fn [e] ((apply with {:state severity :description message} children) e)))

(def informational (partial severity "informational"))
(def normal (partial severity "normal"))
(def warning (partial severity "warning"))
(def minor (partial severity "minor"))
(def major (partial severity "major"))
(def critical (partial severity "critical"))

(defn edge-detection
  [samples & children]
  (let [detector (by [:host :service] (runs samples :state (apply changed :state children)))]
    (fn [e] (detector e))))


(defn proportion
  [events]
  (when-let [event (first events)]
    (try
      (riemann.folds/fold-all (fn [a b] (/ a (+ a b))) events)
      (catch NullPointerException expired
        (merge event :metric nil)))))

; thresholding
(let [index (default :ttl 60 (index))
      alert (logstash {:host "127.0.0.1"
                       :port 25827
                       :protocol :tcp})
      dedup-alert (edge-detection 1 log-info alert)
      dedup-2-alert (edge-detection 2 log-info alert)
      dedup-4-alert (edge-detection 4 log-info alert)
      graph (async-queue! :opentsdb {:queue-size 1000}
                          (opentsdb {:host "127.0.0.1"}))]
  (streams (parse-stream
             (let [cpu-load-one
                   (by [:host]
                       (match :service "icinga.riemann.UNIX.load.load1"
                              (splitp < metric
                                2.0 (minor "System 1-minute load average is very high" dedup-alert)
                                4.0 (warning "System 1-minute load average is high" dedup-alert)
                                (normal "System 1-minute load average is OK" dedup-alert))))
                   cpu-load-five
                   (by [:host]
                       (match :service "icinga.riemann.UNIX.load.load5"
                              (splitp < metric
                                1.5 (minor "System 5-minute load average is very high" dedup-alert)
                                3.0 (warning "System 5-minute load average is high" dedup-alert)
                                (normal "System 5-minute load average is OK" dedup-alert))))
                   cpu-load-fivteen
                   (by [:host]
                       (match :service "icinga.riemann.UNIX.load.load15"
                              (splitp < metric
                                1.2 (minor "System 15-minute load average is very high" dedup-alert)
                                2.4 (warning "System 15-minute load average is high" dedup-alert)
                                (normal "System 15-minute load average is OK" dedup-alert))))]

               (where (not (state "expired"))
                      cpu-load-one
                      cpu-load-five
                      cpu-load-fivteen))))
  (streams
    ;(with {:metric 1 :host hostname :state "normal" :service "riemann events_sec"}
      ;#(info %)
      (rate 10 index graph)))
; use #(info %) for log
;(let [index (index)]
  ;(streams
    ;index
;    (where (tags "icinga2")
;           (smap (fn [event]
;                   (assoc event :state "ok")
;                   index
;                   graph)))
;      (where (tagged-any "FreeNAS")
;             graph)
;      (where (tagged-any "collectd")
;             graph)
;      logstash-forward
;      (expired
;        (fn [event] (info "EXPIRED" event)))))

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
  ;(def graph
  ;  (opentsdb {:host host}))
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
                                 :host (str (:ip e) ":" (:host e))
                                 :resource (:host e)
                                 :type "gangliaAlert")]
            (call-rescue new-event children))))

(defn log-info
  [e]
  (info e))

; reap expired events every 10 seconds
(periodically-expire 10 {:keep-keys [:host :service :environment :resource :grid :cluster :ip :tags :metric :index-time]})

; some helpful functions
(defn now []
  (Math/floor (unix-time)))

(defn switch-epoch-to-elapsed
  [& children]
  (fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn state-to-metric
  [& children]
  (fn [e] ((apply with {:metric (:state e)} children) e)))

(defn lookup-metric
  [metricname & children]
  (let [metricsymbol (keyword metricname)]
    (fn [e]
      (let [metricevent (.lookup (:index @core) (:host e) metricname)]
        (if-let [metricvalue (:metric metricevent)]
          (call-rescue (assoc e metricsymbol metricvalue) children))))))

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

(defn set-resource-from-cluster [e] (assoc e :resource (:cluster e)))

(defn proportion
  [events]
  (when-let [event (first events)]
    (try
      (riemann.folds/fold-all (fn [a b] (/ a (+ a b))) events)
      (catch NullPointerException expired
        (merge event :metric nil)))))

; thresholding
(let [index (default :ttl 900 (index))
      alert (async-queue! :alerta {:queue-size 10000}
                          (alerta {}))
      dedup-alert (edge-detection 1 log-info alert)
      dedup-2-alert (edge-detection 2 log-info alert)
      dedup-4-alert (edge-detection 4 log-info alert)
      graph (async-queue! :opentsdb {:queue-size 1000}
                          (opentsdb {:host "127.0.0.1"}))]
  (streams (parse-stream
             (let [cpu-load-five
                   (by [:host]
                       (match :service "icinga.riemann.UNIX.load.load5"
                              (with {:event "SystemLoad" :group "OS"}
                                (lookup-metric "cpu_num"
                                               (split*
                                                 (fn [e] (< (* 10 (:cpu_num e)) (:metric e))) (minor "System 5-minute load average is very high" dedup-alert)
                                                 (fn [e] (< (* 6 (:cpu_num e)) (:metric e))) (warning "System 5-minute load average is high" dedup-alert)
                                                 (normal "System 5-minute load average is OK" dedup-alert))))))]
               (where (not (state "expired"))
                      cpu-load-five))))
  (streams
    (with {:metric 1 :host hostname :state "normal" :service "riemann events_sec"}
      (rate 10 index graph))))

; use #(info %) for log
;(let [index (index)]
;  (streams
;    index
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

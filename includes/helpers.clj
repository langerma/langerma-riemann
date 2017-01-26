;
; parsers for graphite inputs
;
(defn graphite-event-parser-icinga
  [{:keys [service] :as event}]
  (if-let [[source hostname checktype servicename checkname perfdata metricname] (string/split service #"\." 7)]
    (if-let [[servicetype realservicename] (clojure.string/split servicename #"_" 2)]
      {:host (string/replace hostname #"_" ".")
       :service (string/join "." ["icinga" "riemann" servicetype realservicename (string/replace
                                                                                  (string/replace
                                                                                   (string/replace metricname #"load_1_min|load_15_min|load_5_min" {"load_1_min" "load1" "load_15_min" "load15" "load_5_min" "load5"}) #"\.value" "") #"check_snmp\." "")])
       :metric (:metric event)
       :tags source
       :checktype checktype
       ;:state nil
       :servicetype servicetype
       :time (:time event)
       :ttl 60})))

(defn graphite-event-parser-freenas
  [{:keys [service] :as event}]
  (if-let [[hostname metricname] (string/split service #"\." 2)]
  {:host "at-vie-fn01"
   :service (string/join "." ["freenas" (string/replace metricname #"\.value" "")])
   :metric (:metric event)
   :tags ["FreeNAS"]
   ;:state nil
   :time (:time event)
   :ttl 60}))

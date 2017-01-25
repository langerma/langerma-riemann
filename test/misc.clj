(defn log-info [event]
  (info event))

(defn now []
  (Math/floor (unix-time)))

(defn switch-epoch-to-elapsed [& children]
  (fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn lookup-metric [metricname e & children]
  (let [metricsymbol (keyword metricname)]
      (let [metricevent (.lookup (:index @core) (:host e) metricname)]
        (if-let [metricvalue (:metric metricevent)]
          metricvalue))))

(defn edge-detection [samples & children]
  (let [detector (by [:host :service] (runs samples :state (apply changed :state children)))]
    (fn [e] (detector e))))

(defn set-resource-from-cluster [event]
  (assoc event :resource (:cluster event)))

(defn sum-service-metrics-by-host [match & children]
  (where (service match)
    (by :host
      (coalesce
        (smap folds/sum
          (fn [e]
            (call-rescue e children)))))))

(defn inject-event [& children]
  (fn [e]
    (core/stream! @core e)
    (call-rescue e children)))

; Note: We need support for OpenTSDB first
(def graph
  (if (resolve 'local-testing)
    log-info
    (fn [event]
      (str event))))

(defn calc-percent-over-time [& children]
  (fn [events]
    (let [[min-metric max-metric last-event]
          [(:metric (apply min-key :metric events))
           (:metric (apply max-key :metric events))
           (last events)]]
      (if (not (zero? min-metric))
        (let [percent (/ max-metric min-metric)]
          (call-rescue last-event children))
        (let [percent 0])

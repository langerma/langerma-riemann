(logging/init {:file "/var/log/riemann/riemann.log"})
; Include the Subfiles
; Note: Extract it to riemann-extensions
(include "misc.clj")
(include "severity.clj")
(include "threshold.clj")

; local testing
;(def local-testing true)

; hostname
(def hostname (.getCanonicalHostName (java.net.InetAddress/getLocalHost)))

; ### BEGIN RIEMANN CFG ###
;(logging/init {:file "/var/log/riemann/riemann.log"})

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server :host host)
  (udp-server :host host)
  (ws-server  :host host))

; Expire old events from the index every 10 seconds.
(periodically-expire 10 {:keep-keys [:host :service :environment :grid :cluster :ip :tags :metric :index-time]})

; Keep events in the index for 15 minutes by default.
(let [index (default :ttl 900 (update-index (index)))
      dedup-1-alert (edge-detection 1 log-info)
      dedup-2-alert (edge-detection 2 log-info)
      dedup-4-alert (edge-detection 4 log-info)]

  ; Inbound events will be passed to these streams:

  ; set the index-time of the event
  (streams
    (with :index-time (format "%.0f" (now)) index)

  ; for now only deal with expired ping events in a critical fashion
    (expired
      (match :service "ping"
        (fn [event]
          (info "expired ping" event)
          (index event))))

  ; count unique hosts
    (let [hosts (atom #{})]
      (fn [event]
        (swap! hosts conj (:host event))
        (index {:service "unique.hosts"
                :time (unix-time)
                :host hostname
                :metric (count @hosts)})))

  ; count critical hosts
    (where (state "critical")
      (let [hosts (atom #{})]
        (fn [event]
          (swap! hosts conj (:host event))
          (index {:service "critical.hosts"
                  :time (unix-time)
                  :host hostname
                  :metric (count @hosts)}))))

    (sum-service-metrics-by-host #"riemann.cpu-\d+/cpu-idle"
      (with :service "riemann.cpu.idle"
        (inject-event index)))

    (sum-service-metrics-by-host #"riemann.cpu-\d+/cpu-user"
      (with :service "riemann.cpu.user"
        (inject-event index)))

    (sum-service-metrics-by-host #"riemann.cpu-\d+/cpu-system"
      (with :service "riemann.cpu.system"
        (inject-event index)))

    (sum-service-metrics-by-host #"riemann.cpu-\d+/cpu-wait"
      (with :service "riemann.cpu.wait"
        (inject-event index)))

    (sum-service-metrics-by-host #"riemann.cpu-\d+/cpu"
      (with :service "cpu.total"
        (inject-event index)))

    (where
      (service "riemann.cpu.idle")
      (fn [event]
        (if-let [metric (:metric event)]
          (if-let [total (lookup-metric "riemann.cpu.total" event)]
            ((split
              (< metric (* total 0.20)) (with :state "warning" (inject-event index))
              (< metric (* total 0.15)) (with :state "critical" (inject-event index))
              (with :state "ok" index)) event)))))

    (where
      (service "riemann.cpu.wait")
      (fn [event]
        (if-let [metric (:metric event)]
          (if-let [total (lookup-metric "riemann.cpu.total" event)]
            ((split
              (> metric (* total 0.05)) (with :state "warning" (inject-event index))
              (> metric (* total 0.10)) (with :state "critical" (inject-event index))
              (with :state "ok" index)) event)))))

    (where (service #"^riemann.df-(?:(?!dev|run).+)$")
      (by :host
        (fn [event]
          (if-let [mountpoint (get (re-find #"^riemann.df-(\S+)/.*$" (:service event)) 1)]
            (if-let [item (get (re-find #"^riemann.df-(\S+)/.+-(\S+)$" (:service event)) 2)]
              ((with :service (str "df." mountpoint "." item) index) event))))))

    (where (service "riemann.entropy/entropy")
      (by :host
        (fixed-time-window 30
          (combine folds/mean
            (with :service "riemann.entropy.mean" inject-event)))))

    (where (service "riemann.entropy.mean")
      (splitp > metric
        20 (with :state "critical" (inject-event index))
        45 (with :state "warning" (inject-event index))
           (with :state "ok" (inject-event index))))

    (where (service "riemann.processes/ps_state-zombies")
      (by :host
        (splitp < metric
          1 (with {:state "critical" :service "riemann.processes.zombies"} (inject-event index))
            (with {:state "ok" :service "riemann.processes.zombies"} (inject-event index)))))

    (where (service "riemann.processes/fork_rate")
      (by :host
        (fixed-time-window 30
          (smap
            (fn [events]
              (let [[min-metric max-metric last-event]
                    [(:metric (apply min-key :metric events))
                     (:metric (apply max-key :metric events))
                     (last events)]]
                (if (not (zero? min-metric))
                  (let [percent (/ max-metric min-metric)]
                    (cond
                      (> percent 100) ((with {:ttl 40 :state "critical" :service "riemann.processes.forkrate.increase" :metric percent} (inject-event index)) last-event)
                      (> percent 60)  ((with {:ttl 40 :state "warning" :service "riemann.processes.forkrate.increase" :metric percent} (inject-event index)) last-event)
                      :else ((with {:ttl 40 :state "ok" :service "riemann.processes.forkrate.increase" :metric percent} (inject-event index)) last-event)))
                  ((with {:ttl 40 :state "ok" :service "riemann.processes.forkrate.increase"} (inject-event index)) last-event)
                )))))))

    ; Calculate an overall rate of events.
    (with {:metric 1 :host hostname :state "ok" :service "events/sec"}
      (rate 10 index))

    ; log state changes
    (let [hosts (atom {})]
      (changed-state
        (fn [event]
          (let [host (keyword (:host event))]
            (if (contains? @hosts host)
              (swap! hosts update-in [host] inc)
              (swap! hosts conj {host 1}))
            (index {:service "riemann.changed.states"
                    :time (unix-time)
                    :host (:host event)
                    :metric (host @hosts)})))))

    (where
      (service "ping")
      (percentiles 30 [0.5 0.95 0.99] index)
      (default {:state "ok" :ttl 60} index))
  )
#(info %))

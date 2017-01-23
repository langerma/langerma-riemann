(def thresholds
  {"cpu-average/cpu-user" {:warning 30 :critical 60}
   "cpu-average/cpu-system" {:warning 30 :critical 60}
   "cpu-average/cpu-used" {:warning 60 :critical 90}
   "cpu-average/cpu-nice" {:warning 50 :critical 20}
   "cpu-average/cpu-idle" {:warning 50 :critical 20 :invert true}
   "cpu-average/cpu-steal" {:warning 50 :critical 20}

   "load/load/shortterm" {:warning 3 :critical 4}
   "load/load/midterm" {:warning 3 :critical 4}
   "load/load/longterm" {:warning 3 :critical 4}

   "icinga.riemann.UNIX.load.load1" {:warning 2 :critical 4}
   "icinga.riemann.UNIX.load.load5" {:warning 3 :critical 5}
   "icinga.riemann.UNIX.load.load15" {:warning 4 :critical 6}

   "processes/ps_state-blocked" {:warning 4 :critical 8}
   "processes/ps_state-paging" {:warning 4 :critical 8}
   "processes/ps_state-running" {:warning 16 :critical 24}
   "processes/ps_state-sleeping" {:warning 500 :critical 1000}
   "processes/ps_state-stopped" {:warning 1 :critical 8}
   "processes/ps_state-zombies" {:warning 0 :critical 8}

   "memory/memory-buffered" {}
   "memory/memory-cached" {}
   "memory/memory-free" {:warning 0.10
                         :critical 0.05
                         :invert true}
   "memory/memory-used" {}
   "memory/percent-used" {:warning 80
                          :critical 98}

   "icinga.riemann.UNIX.memory.ACTIVE" {}
   "icinga.riemann.UNIX.memory.BUF" {}
   "icinga.riemann.UNIX.memory.CACHE" {}
   "icinga.riemann.UNIX.memory.FBSD_MEM" {}
   "icinga.riemann.UNIX.memory.INACTIVE" {}
   "icinga.riemann.UNIX.memory.USED" {}
   "icinga.riemann.UNIX.memory.WIRED" {}
   "icinga.riemann.UNIX.memory.quotient" {:warning 80
                                          :critical 95}
   "swap/swap-cached" {}
   "swap/swap-free" {}
   "swap/swap-used" {}
   "swap/swap_io-in" {}
   "swap/swap_io-out" {}

   "uptime/uptime" {:warning 1 :critical 0 :invert true}

   "df-root/percent_bytes-free" {:warning 10 :critical 6 :invert true}

   "interface-wlan0/if_octets/rx" {:warning 768 :critical 1024}
   "interface-wlan0/if_octets/tx" {:warning 768 :critical 1024}
   "interface-eth0/if_octets/rx" {}
   "interface-eth0/if_octets/tx" {}

   "tail-auth/counter-sshd-invalid_user" {:warning 0 :critical 10}
   "tail-auth/counter-sshd-successful-logins" {}
   })

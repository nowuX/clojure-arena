(ns clojure-arena.heartbeat
  (:import [java.nio ByteBuffer]
           [java.util Arrays]
           [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture
            ThreadFactory TimeUnit]))

(def probe-ms 5000)
(def timeout-ms 15000)
(def ^:private timeout-ns (* timeout-ms 1000000))

(defn- safely! [action]
  (try (action)
       (catch Exception error
         (binding [*out* *err*]
           (println "Heartbeat callback failed:" (.getMessage error))))))

(defn make-driver
  ([probe!] (make-driver probe! #(System/nanoTime)))
  ([probe! now-ns]
   (let [gate (Object.)
         state (atom {:running? true :sequence 0 :connections {}})]
     {:register! (fn [token on-timeout!]
                   (locking gate
                     (when (and (:running? @state)
                                (not (contains? (:connections @state) token)))
                       (swap! state assoc-in [:connections token]
                              {:last-pong-at (now-ns)
                               :challenge nil
                               :on-timeout! on-timeout!}))))
      :forget! (fn [token]
                 (locking gate
                   (swap! state update :connections dissoc token)))
      :pong! (fn [token payload]
               (locking gate
                 (let [{:keys [last-pong-at challenge]}
                       (get-in @state [:connections token])
                       now (now-ns)]
                   (when (and (:running? @state) challenge
                              (< (- now last-pong-at) timeout-ns)
                              (Arrays/equals ^bytes challenge ^bytes payload))
                     (swap! state update-in [:connections token]
                            assoc :last-pong-at now :challenge nil)))))
      :pulse! (fn []
                (let [actions
                      (locking gate
                        (when (:running? @state)
                          (let [now (now-ns)
                                [next-state actions]
                                (reduce-kv
                                 (fn [[next-state actions] token
                                      {:keys [last-pong-at challenge on-timeout!]}]
                                   (cond
                                     (>= (- now last-pong-at) timeout-ns)
                                     [(update next-state :connections dissoc token)
                                      (conj actions on-timeout!)]

                                     (nil? challenge)
                                     (let [number (inc (:sequence next-state))
                                           payload (.array (doto (ByteBuffer/allocate 8)
                                                             (.putLong number)))]
                                       [(-> next-state
                                            (assoc :sequence number)
                                            (assoc-in [:connections token :challenge]
                                                      payload))
                                        (conj actions #(probe! token payload))])

                                     :else [next-state actions]))
                                 [@state []] (:connections @state))]
                            (reset! state next-state)
                            actions)))]
                  (doseq [action actions] (safely! action))))
      :stop! (fn []
               (locking gate
                 (swap! state assoc :running? false :connections {})))})))

(defn start! [driver]
  (let [executor (Executors/newSingleThreadScheduledExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. runnable "clojure-arena-heartbeat")
                        (.setDaemon true)))))]
    (try
      {:driver driver
       :executor executor
       :task (.scheduleWithFixedDelay executor ^Runnable (:pulse! driver)
                                     probe-ms probe-ms TimeUnit/MILLISECONDS)}
      (catch Exception error
        ((:stop! driver))
        (.shutdownNow executor)
        (throw error)))))

(defn stop! [{:keys [driver executor task] :as heartbeat}]
  (when heartbeat
    ((:stop! driver))
    (.cancel ^ScheduledFuture task false)
    (.shutdownNow ^ScheduledExecutorService executor)
    (when-not (.awaitTermination ^ScheduledExecutorService executor 1000
                                 TimeUnit/MILLISECONDS)
      (throw (ex-info "Heartbeat executor did not stop before the deadline" {}))))
  nil)

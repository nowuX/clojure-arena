(ns clojure-arena.publication
  (:import [java.util.concurrent ArrayBlockingQueue ExecutorService Executors
            ThreadFactory TimeUnit]))

(defn start! [deliver!]
  (let [queue (ArrayBlockingQueue. 1)
        running? (atom true)
        gate (Object.)
        executor (Executors/newSingleThreadExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. runnable "clojure-arena-publication")
                        (.setDaemon true)))))
        handle {:queue queue :running? running? :gate gate :executor executor}]
    (try
      (.execute executor
                ^Runnable
                (fn []
                  (try
                    (while @running?
                      (let [effect (.take queue)]
                        (when @running?
                          (try
                            (deliver! effect)
                            (catch InterruptedException error (throw error))
                            (catch Exception error
                              (binding [*out* *err*]
                                (println "Snapshot delivery failed:" (.getMessage error))))))))
                    (catch InterruptedException _ nil))))
      handle
      (catch Exception error
        (reset! running? false)
        (.shutdownNow executor)
        (throw error)))))

(defn publish! [{:keys [queue running? gate]} effect]
  (locking gate
    (when @running?
      (.poll ^ArrayBlockingQueue queue)
      (.offer ^ArrayBlockingQueue queue effect)))
  nil)

(defn stop! [{:keys [queue running? gate executor] :as handle}]
  (when handle
    (locking gate
      (reset! running? false)
      (.clear ^ArrayBlockingQueue queue))
    (.shutdownNow ^ExecutorService executor)
    (when-not (.awaitTermination ^ExecutorService executor
                                1000 TimeUnit/MILLISECONDS)
      (throw (ex-info "Snapshot publisher did not stop before the deadline" {}))))
  nil)

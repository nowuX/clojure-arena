(ns clojure-arena.ticker
  (:require [clojure-arena.room :as room])
  (:import [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture
            ThreadFactory TimeUnit]))

(def tick-ms 25)

(defn make-driver [room-agent]
  (let [gate (Object.)
        running? (atom true)
        pending? (atom false)]
    {:pulse! (fn []
               (locking gate
                 (when (and @running? (compare-and-set! pending? false true))
                   (try
                     (send room-agent
                           (fn [{:keys [room] :as envelope}]
                             (try
                               (if @running?
                                 (room/apply-room-message room {:type :tick})
                                 envelope)
                               (finally
                                 (reset! pending? false)))))
                     (catch Exception error
                       (reset! pending? false)
                       (throw error))))))
     :stop! (fn []
              (locking gate
                (reset! running? false)))}))

(defn start! [room-agent]
  (let [driver (make-driver room-agent)
        executor (Executors/newSingleThreadScheduledExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. runnable "clojure-arena-ticker")
                        (.setDaemon true)))))]
    (try
      {:room-agent room-agent
       :driver driver
       :executor executor
       :task (.scheduleWithFixedDelay executor
                                     ^Runnable (:pulse! driver)
                                     tick-ms tick-ms TimeUnit/MILLISECONDS)}
      (catch Exception error
        ((:stop! driver))
        (.shutdownNow executor)
        (throw error)))))

(defn stop! [{:keys [driver executor task room-agent] :as ticker}]
  (when ticker
    ((:stop! driver))
    (.cancel ^ScheduledFuture task false)
    (.shutdownNow ^ScheduledExecutorService executor)
    (when-not (.awaitTermination ^ScheduledExecutorService executor 1000
                                 TimeUnit/MILLISECONDS)
      (throw (ex-info "Ticker executor did not stop before the deadline" {})))
    (when (and (nil? (agent-error room-agent))
               (not (await-for 1000 room-agent)))
      (throw (ex-info "Room actions did not settle before the shutdown deadline" {}))))
  nil)

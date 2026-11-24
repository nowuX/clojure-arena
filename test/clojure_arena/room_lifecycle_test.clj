(ns clojure-arena.room-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- await-agent! [room-agent]
  (is (await-for 3000 room-agent)
      "room agent should settle its queued operations before the deadline")
  (is (nil? (agent-error room-agent))
      "room actions and the post-commit callback should not fail the agent"))

(defn- enqueue-concurrently! [room-agent messages]
  (let [request-count (count messages)
        ready (CountDownLatch. request-count)
        start (CountDownLatch. 1)
        finished (CountDownLatch. request-count)
        failures (atom [])
        threads (mapv (fn [message]
                        (doto (Thread.
                               ^Runnable
                               (reify Runnable
                                 (run [_]
                                   (.countDown ready)
                                   (try
                                     (when-not (.await start 2 TimeUnit/SECONDS)
                                       (throw (ex-info "start latch timed out" {})))
                                     (room/enqueue-room-message! room-agent message)
                                     (catch Throwable error
                                       (swap! failures conj error))
                                     (finally
                                       (.countDown finished))))))
                          (.setDaemon true)))
                      messages)]
    (try
      (doseq [thread threads]
        (.start thread))
      (is (.await ready 2 TimeUnit/SECONDS)
          "all concurrent room submitters should reach the start latch")
      (.countDown start)
      (is (.await finished 2 TimeUnit/SECONDS)
          "all concurrent room submitters should enqueue before the deadline")
      (is (empty? @failures) (pr-str @failures))
      (await-agent! room-agent)
      (finally
        (.countDown start)
        (doseq [thread threads]
          (.join thread 100))))))

(deftest concurrent-owned-input-tick-leave-and-rejoin-preserve-room-invariants-test
  (let [deliveries (atom [])
        room-agent (room/make-room #(swap! deliveries conj %))]
    (try
      (doseq [connection-token [:old-session :steady-session]]
        (room/enqueue-room-message! room-agent
                                    {:type :join
                                     :connection-token connection-token}))
      (await-agent! room-agent)
      (enqueue-concurrently!
       room-agent
       [{:type :input
         :connection-token :old-session
         :input (assoc protocol/neutral-input :horizontal 1 :jump? true :attack? true)}
        {:type :input
         :connection-token :steady-session
         :input (assoc protocol/neutral-input :horizontal -1 :drop? true)}
        {:type :tick}
        {:type :leave :connection-token :old-session}
        {:type :join :connection-token :new-session}])
      (let [final-envelope @room-agent
            final-room (:room final-envelope)
            snapshot-effects (filterv #(= :snapshot (get-in % [:message :type]))
                                      (mapcat identity @deliveries))
            snapshot (first snapshot-effects)
            recipient-player-ids
            (set (keep {:old-session :player-1
                        :steady-session :player-2
                        :new-session :player-3}
                       (:connection-tokens snapshot)))]
        (is (= #{:steady-session :new-session}
               (set (keys (:members final-room)))))
        (is (= #{:player-2 :player-3}
               (set (keys (get-in final-room [:world :players])))))
        (is (= #{:player-2 :player-3}
               (set (keys (:inputs-by-player final-room)))))
        (is (= 1 (:tick final-room)))
        (is (= 4 (:next-player-number final-room)))
        (is (= :player-3
               (get-in final-room [:members :new-session :player-id])))
        (is (contains? #{:cyan :amber}
                       (get-in final-room [:members :new-session :color]))
            "the new token uses the freed color or the next free color")
        (is (= 2 (count (set (map :color (vals (:members final-room))))))
            "concurrent admission must not assign one color twice")
        (is (= protocol/neutral-input
               (get-in final-room [:inputs-by-player :player-3]))
            "a replacement token receives fresh neutral input")
        (is (= -1 (get-in final-room [:inputs-by-player :player-2 :horizontal]))
            "an accepted direction remains owned by its submitting member")
        (is (= 1 (count snapshot-effects))
            "the concurrent tick should capture exactly one snapshot")
        (is (= 1 (get-in snapshot [:message :tick])))
        (is (= recipient-player-ids
               (set (keys (get-in snapshot [:message :world :players]))))
            "captured recipients and world players should describe the same tick"))
      (finally
        (await-for 3000 room-agent)))))

(deftest concurrent-finish-reset-and-start-boundaries-retain-one-consistent-world
  (let [room-agent (room/make-room (fn [_] nil))
        commits (atom [])
        watch-key ::match-boundaries
        input (assoc protocol/neutral-input :horizontal 1 :jump? true :drop? true :attack? true)
        seed! (fn [path value]
                (send room-agent (fn [envelope] (-> envelope (assoc :effects []) (assoc-in path value))))
                (await-agent! room-agent))]
    (try
      (doseq [token [:a :b]]
        (room/enqueue-room-message! room-agent {:type :join :connection-token token}))
      (dotimes [_ 120] (room/enqueue-room-message! room-agent {:type :tick}))
      (await-agent! room-agent)
      (seed! [:room :world :match :time-seconds-left] 0.025)
      (add-watch room-agent watch-key (fn [_ _ _ next-state] (swap! commits conj (:room next-state))))
      (enqueue-concurrently! room-agent [{:type :tick} {:type :input :connection-token :a :input input}])
      (is (= :finished (get-in @room-agent [:room :world :match :phase])))
      (is (= 5 (get-in @room-agent [:room :world :match :intermission-seconds-left])))
      (is (= (assoc protocol/neutral-input :horizontal 1) (get-in @room-agent [:room :inputs-by-player :player-1])))
      (seed! [:room :world :match :intermission-seconds-left] 0.025)
      (enqueue-concurrently! room-agent [{:type :tick} {:type :join :connection-token :c}
                                         {:type :leave :connection-token :a}])
      (let [state (:room @room-agent)]
        (is (= #{:b :c} (set (keys (:members state)))))
        (is (= :countdown (get-in state [:world :match :phase])))
        (is (= 3 (get-in state [:world :match :countdown-seconds-left])))
        (is (empty? (get-in state [:world :match :contenders]))))
      (room/enqueue-room-message! room-agent {:type :join :connection-token :e})
      (await-agent! room-agent)
      (seed! [:room :world :match :countdown-seconds-left] 0.025)
      (enqueue-concurrently! room-agent [{:type :tick} {:type :join :connection-token :d}
                                         {:type :leave :connection-token :c}])
      (let [state (:room @room-agent)
            ids (set (map :player-id (vals (:members state))))]
        (is (= #{:b :d :e} (set (keys (:members state)))))
        (is (= ids (set (keys (:inputs-by-player state)))))
        (is (= ids (set (keys (get-in state [:world :players])))))
        (is (= :running (get-in state [:world :match :phase])))
        (is (= 2 (get-in state [:world :match :id])))
        (is (= 180 (get-in state [:world :match :time-seconds-left]))))
      (doseq [state @commits]
        (is (= (set (map :player-id (vals (:members state))))
               (set (keys (get-in state [:world :players])))
               (set (keys (:inputs-by-player state)))))
        (is (protocol/snapshot-message? {:type :snapshot :tick (:tick state) :world (:world state)})))
      (finally (remove-watch room-agent watch-key) (await-for 3000 room-agent)))))

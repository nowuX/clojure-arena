(ns clojure-arena.ticker-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.game :as game]
            [clojure-arena.room :as room]
            [clojure-arena.ticker :as ticker])
  (:import [java.util.concurrent CountDownLatch ScheduledExecutorService TimeUnit]))

(defn- await-room! [room-agent]
  (is (await-for 2000 room-agent))
  (is (nil? (agent-error room-agent))))

(deftest controlled-pulses-produce-forty-fixed-steps
  (let [room-agent (room/make-room (fn [_]))
        driver (ticker/make-driver room-agent)]
    (try
      (room/enqueue-room-message! room-agent {:type :join :connection-token :first})
      (await-room! room-agent)
      (let [initial-world (get-in @room-agent [:room :world])]
        (dotimes [_ 40]
          ((:pulse! driver))
          (await-room! room-agent))
        (is (= 40 (get-in @room-agent [:room :tick])))
        (is (= (nth (iterate #(game/step % {} 0.025) initial-world) 40)
               (get-in @room-agent [:room :world]))))
      (finally
        ((:stop! driver))))))

(deftest slow-room-keeps-only-one-pending-tick-and-drops-missed-pulses
  (let [room-agent (room/make-room (fn [_]))
        driver (ticker/make-driver room-agent)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (try
      (send room-agent (fn [envelope]
                         (.countDown entered)
                         (when-not (.await release 2 TimeUnit/SECONDS)
                           (throw (ex-info "Test room release timed out" {})))
                         envelope))
      (is (.await entered 2 TimeUnit/SECONDS))
      (dotimes [_ 1000] ((:pulse! driver)))
      (.countDown release)
      (await-room! room-agent)
      (is (= 1 (get-in @room-agent [:room :tick])))
      ((:pulse! driver))
      (await-room! room-agent)
      (is (= 2 (get-in @room-agent [:room :tick]))
          "missed pulses must not generate later catch-up ticks")
      (finally
        (.countDown release)
        ((:stop! driver))))))

(deftest stopped-driver-discards-queued-ticks-even-after-a-new-driver-starts
  (let [room-agent (room/make-room (fn [_]))
        old-driver (ticker/make-driver room-agent)
        new-driver (ticker/make-driver room-agent)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (try
      (send room-agent (fn [envelope]
                         (.countDown entered)
                         (when-not (.await release 2 TimeUnit/SECONDS)
                           (throw (ex-info "Test room release timed out" {})))
                         envelope))
      (is (.await entered 2 TimeUnit/SECONDS))
      ((:pulse! old-driver))
      ((:stop! old-driver))
      ((:pulse! old-driver))
      ((:pulse! new-driver))
      (.countDown release)
      (await-room! room-agent)
      (is (= 1 (get-in @room-agent [:room :tick]))
          "only the new driver's tick may advance the room")
      (finally
        (.countDown release)
        ((:stop! old-driver))
        ((:stop! new-driver))))))

(deftest scheduled-clock-stops-and-settles-its-room
  (let [reached (CountDownLatch. 1)
        room-agent (room/make-room (fn [_]))
        _ (add-watch room-agent ::ticks
                     (fn [_ _ _ envelope]
                       (when (>= (get-in envelope [:room :tick]) 2)
                         (.countDown reached))))
        handle (ticker/start! room-agent)]
    (try
      (is (.await reached 3 TimeUnit/SECONDS))
      (ticker/stop! handle)
      (let [stopped @room-agent]
        (dotimes [_ 10] ((get-in handle [:driver :pulse!])))
        (await-room! room-agent)
        (is (identical? stopped @room-agent))
        (is (.isTerminated ^ScheduledExecutorService (:executor handle))))
      (ticker/stop! handle)
      (finally
        (remove-watch room-agent ::ticks)
        (ticker/stop! handle)))))

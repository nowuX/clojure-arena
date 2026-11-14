(ns clojure-arena.publication-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.publication :as publication]
            [clojure-arena.room :as room])
  (:import [java.util.concurrent CountDownLatch ExecutorService LinkedBlockingQueue
            TimeUnit]))

(defn- await-latch! [latch]
  (is (.await ^CountDownLatch latch 3 TimeUnit/SECONDS)))

(defn- next-delivery! [deliveries]
  (or (.poll ^LinkedBlockingQueue deliveries 3000 TimeUnit/MILLISECONDS)
      (throw (ex-info "Snapshot delivery timed out" {}))))

(deftest slow-delivery-retains-captured-data-and-only-the-latest-pending-snapshot
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        deliveries (LinkedBlockingQueue.)
        effects (mapv (fn [tick]
                        {:connection-tokens [:connection]
                         :message {:type :snapshot :tick tick :world {:value tick}}})
                      [1 2 3])
        handle (publication/start!
                 (fn [effect]
                   (when (= 1 (get-in effect [:message :tick]))
                     (.countDown entered)
                     (when-not (.await release 3 TimeUnit/SECONDS)
                       (throw (ex-info "Blocked delivery was not released" {}))))
                   (.offer deliveries effect)))]
    (try
      (publication/publish! handle (first effects))
      (await-latch! entered)
      (publication/publish! handle (second effects))
      (publication/publish! handle (last effects))
      (.countDown release)
      (is (= (first effects) (next-delivery! deliveries)))
      (is (= (last effects) (next-delivery! deliveries))
          "pending replacement must not relabel an older world's tick")
      (publication/stop! handle)
      (is (empty? deliveries))
      (finally
        (.countDown release)
        (publication/stop! handle)))))

(deftest blocked-snapshot-delivery-does-not-block-room-ticks
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        deliveries (LinkedBlockingQueue.)
        handle (publication/start!
                 (fn [effect]
                   (when (= 1 (get-in effect [:message :tick]))
                     (.countDown entered)
                     (when-not (.await release 3 TimeUnit/SECONDS)
                       (throw (ex-info "Blocked delivery was not released" {}))))
                   (.offer deliveries effect)))
        room-agent (room/make-room
                    (fn [effects]
                      (doseq [effect effects :when (:connection-tokens effect)]
                        (publication/publish! handle effect))))]
    (try
      (room/enqueue-room-message! room-agent {:type :join :connection-token :one})
      (room/enqueue-room-message! room-agent {:type :tick})
      (await-latch! entered)
      (dotimes [_ 40] (room/enqueue-room-message! room-agent {:type :tick}))
      (is (await-for 2000 room-agent))
      (is (nil? (agent-error room-agent)))
      (is (= 41 (get-in @room-agent [:room :tick]))
          "simulation continues while the publication worker is blocked")
      (.countDown release)
      (let [first-message (:message (next-delivery! deliveries))
            last-message (:message (next-delivery! deliveries))]
        (is (= 1 (:tick first-message)))
        (is (= 41 (:tick last-message)))
        (is (= (get-in @room-agent [:room :world]) (:world last-message)))
        (is (not= (:world first-message) (:world last-message))))
      (finally
        (.countDown release)
        (publication/stop! handle)))))

(deftest stop-discards-waiting-snapshots-and-terminates-in-flight-delivery
  (let [entered (CountDownLatch. 1)
        block (CountDownLatch. 1)
        started (atom [])
        handle (publication/start!
                 (fn [effect]
                   (swap! started conj effect)
                   (.countDown entered)
                   (.await block)))]
    (try
      (publication/publish! handle :first)
      (await-latch! entered)
      (publication/publish! handle :pending)
      (publication/stop! handle)
      (publication/publish! handle :late)
      (is (= [:first] @started))
      (is (.isTerminated ^ExecutorService (:executor handle)))
      (is (empty? (:queue handle)))
      (publication/stop! handle)
      (finally
        (.countDown block)
        (publication/stop! handle)))))

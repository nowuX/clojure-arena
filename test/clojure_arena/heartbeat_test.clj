(ns clojure-arena.heartbeat-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.heartbeat :as heartbeat])
  (:import [java.io StringWriter]
           [java.util.concurrent ScheduledExecutorService ScheduledFuture]))

(def ^:private nanos-per-millisecond 1000000)

(defn- fake-clock []
  (let [time (atom 0)]
    {:now-ns #(deref time)
     :advance-ms! (fn [milliseconds]
                    (swap! time + (* milliseconds nanos-per-millisecond)))}))

(defn- payload-bytes [payload]
  (byte-array payload))

(deftest probes-have-one-outstanding-challenge-per-connection
  (let [clock (fake-clock)
        probes (atom [])
        timeouts (atom [])
        driver (heartbeat/make-driver
                (fn [token payload]
                  (swap! probes conj {:token token :payload (vec payload)}))
                (:now-ns clock))]
    ((:register! driver) :alpha #(swap! timeouts conj :alpha))
    ((:register! driver) :beta #(swap! timeouts conj :beta))
    ((:pulse! driver))
    (is (= 2 (count @probes)))
    (let [[alpha-probe beta-probe] @probes
          alpha-challenge (:payload alpha-probe)
          beta-challenge (:payload beta-probe)]
      (is (= #{:alpha :beta} (set (map :token @probes))))
      (is (not= alpha-challenge beta-challenge)
          "different connections receive distinct challenge bytes")
      ((:pulse! driver))
      (is (= 2 (count @probes))
          "a connection with an outstanding challenge is not probed again")
      ((:pong! driver) :alpha (payload-bytes beta-challenge))
      ((:pong! driver) :beta (payload-bytes alpha-challenge))
      ((:pulse! driver))
      (is (= 2 (count @probes))
          "another connection's challenge cannot acknowledge this connection")
      ((:pong! driver) :alpha (payload-bytes alpha-challenge))
      ((:pong! driver) :alpha (payload-bytes alpha-challenge))
      ((:pulse! driver))
      (is (= 3 (count @probes))
          "a matching pong renews only its connection and clears its challenge")
      (let [next-alpha-challenge (:payload (last @probes))]
        (is (= :alpha (:token (last @probes))))
        (is (not= alpha-challenge next-alpha-challenge)
            "a renewed challenge has different bytes")
        ((:pong! driver) :alpha (payload-bytes alpha-challenge))
        ((:pulse! driver))
        (is (= 3 (count @probes))
            "a stale pong cannot acknowledge a later challenge")))
    ((:stop! driver))))

(deftest pong-renews-the-deadline-only-before-the-exact-timeout-boundary
  (let [clock (fake-clock)
        probes (atom [])
        timeouts (atom 0)
        driver (heartbeat/make-driver
                (fn [_ payload] (swap! probes conj (vec payload)))
                (:now-ns clock))]
    ((:register! driver) :player #(swap! timeouts inc))
    ((:pulse! driver))
    (let [first-challenge (first @probes)]
      ((:advance-ms! clock) 14999)
      ((:pong! driver) :player (payload-bytes first-challenge))
      ((:pulse! driver))
      (is (= 2 (count @probes))
          "a valid pong one millisecond before timeout renews the connection")
      ((:advance-ms! clock) 14999)
      ((:pulse! driver))
      (is (zero? @timeouts)
          "the renewed deadline is measured from the valid pong")
      (is (= 2 (count @probes))
          "the renewed challenge remains outstanding")
      ((:advance-ms! clock) 1)
      ((:pong! driver) :player (payload-bytes (second @probes)))
      ((:pulse! driver))
      (is (= 1 @timeouts)
          "a pong exactly at the renewed timeout boundary is late")
      ((:pulse! driver))
      (is (= 1 @timeouts) "timeout callback runs only once"))
    ((:stop! driver))))

(deftest wrong-and-late-pongs-do-not-renew-a-connection
  (let [clock (fake-clock)
        probes (atom [])
        timeouts (atom 0)
        driver (heartbeat/make-driver
                (fn [_ payload] (swap! probes conj (vec payload)))
                (:now-ns clock))]
    ((:register! driver) :player #(swap! timeouts inc))
    ((:pulse! driver))
    (let [challenge (first @probes)]
      ((:pong! driver) :player (byte-array [1 2 3]))
      ((:advance-ms! clock) 14999)
      ((:pong! driver) :player (byte-array [1 2 3]))
      ((:pulse! driver))
      (is (zero? @timeouts))
      (is (= 1 (count @probes)) "wrong bytes leave the outstanding challenge intact")
      ((:advance-ms! clock) 1)
      ((:pong! driver) :player (payload-bytes challenge))
      ((:pulse! driver))
      (is (= 1 @timeouts) "a matching pong at the exact deadline is rejected")
      ((:pong! driver) :player (payload-bytes challenge))
      ((:pulse! driver))
      (is (= 1 @timeouts) "a late pong cannot revive a timed-out connection"))
    ((:stop! driver))))

(deftest forgetting-and-reregistering-invalidates-the-old-challenge
  (let [clock (fake-clock)
        probes (atom [])
        old-timeouts (atom 0)
        new-timeouts (atom 0)
        driver (heartbeat/make-driver
                (fn [_ payload] (swap! probes conj (vec payload)))
                (:now-ns clock))]
    ((:register! driver) :player #(swap! old-timeouts inc))
    ((:pulse! driver))
    (let [old-challenge (first @probes)]
      ((:forget! driver) :player)
      ((:register! driver) :player #(swap! new-timeouts inc))
      ((:pulse! driver))
      (let [new-challenge (second @probes)]
        (is (not= old-challenge new-challenge)
            "re-registration receives a fresh challenge")
        ((:pong! driver) :player (payload-bytes old-challenge))
        ((:advance-ms! clock) 14999)
        ((:pulse! driver))
        (is (zero? @new-timeouts)
            "the old pong does not renew the re-registered connection")
        ((:advance-ms! clock) 1)
        ((:pulse! driver))
        (is (zero? @old-timeouts) "forgetting removes the old timeout callback")
        (is (= 1 @new-timeouts) "the new registration times out once")))
    ((:stop! driver))))

(deftest callback-failures-do-not-stop-other-connection-actions
  (let [clock (fake-clock)
        healthy-probes (atom 0)
        healthy-timeouts (atom 0)
        driver (heartbeat/make-driver
                (fn [token _]
                  (when (= token :broken)
                    (throw (ex-info "probe failure" {})))
                  (swap! healthy-probes inc))
                (:now-ns clock))]
    ((:register! driver) :broken
     #(throw (ex-info "timeout failure" {})))
    ((:register! driver) :healthy #(swap! healthy-timeouts inc))
    (binding [*err* (StringWriter.)]
      ((:pulse! driver))
      (is (= 1 @healthy-probes)
          "a failed probe callback does not prevent another probe")
      ((:advance-ms! clock) 15000)
      ((:pulse! driver))
      (is (= 1 @healthy-timeouts)
          "a failed timeout callback does not prevent another timeout"))
    ((:stop! driver))))

(deftest duplicate-registration-cannot-extend-the-original-deadline
  (let [clock (fake-clock)
        expired (atom [])
        driver (heartbeat/make-driver (fn [_ _]) (:now-ns clock))]
    ((:register! driver) :player #(swap! expired conj :original))
    ((:advance-ms! clock) 14999)
    ((:register! driver) :player #(swap! expired conj :duplicate))
    ((:advance-ms! clock) 1)
    ((:pulse! driver))
    (is (= [:original] @expired))
    ((:stop! driver))))

(deftest stopping-disables-driver-and-terminates-scheduled-executor
  (let [clock (fake-clock)
        probes (atom 0)
        timeouts (atom 0)
        driver (heartbeat/make-driver
                (fn [_ _] (swap! probes inc))
                (:now-ns clock))]
    ((:register! driver) :player #(swap! timeouts inc))
    (let [{:keys [executor task] :as started} (heartbeat/start! driver)]
      (is (instance? ScheduledExecutorService executor))
      (is (instance? ScheduledFuture task))
      (heartbeat/stop! started)
      (is (.isCancelled ^ScheduledFuture task))
      (is (.isTerminated ^ScheduledExecutorService executor))
      ((:pulse! driver))
      ((:register! driver) :later #(swap! timeouts inc))
      ((:advance-ms! clock) 15000)
      ((:pulse! driver))
      (is (zero? @probes) "a stopped driver emits no probes")
      (is (zero? @timeouts) "a stopped driver emits no timeout callbacks"))))

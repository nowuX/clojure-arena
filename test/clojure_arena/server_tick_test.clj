(ns clojure-arena.server-tick-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.server :as server]
            [clojure-arena.ticker :as ticker]
            [clojure-arena.websocket-test :as websocket-test]
            [org.httpkit.server :as http])
  (:import [java.util.concurrent CountDownLatch ScheduledExecutorService TimeUnit]))

(defn- await-tick! [room-agent target]
  (let [reached (CountDownLatch. 1)
        key (Object.)]
    (add-watch room-agent key
               (fn [_ _ _ envelope]
                 (when (>= (get-in envelope [:room :tick]) target)
                   (.countDown reached))))
    (try
      (when (>= (get-in @room-agent [:room :tick]) target)
        (.countDown reached))
      (is (.await reached 8000 TimeUnit/MILLISECONDS)
          "the server clock should advance independently of client traffic")
      (is (nil? (agent-error room-agent)))
      (finally
        (remove-watch room-agent key)))))

(deftest server-clock-is-independent-of-browser-count-and-stops-on-rebind
  (let [host (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1")
        handles (atom [])
        original-start ticker/start!
        start-server! (fn [port]
                        (with-redefs [ticker/start! (fn [room-agent]
                                                     (let [handle (original-start room-agent)]
                                                       (swap! handles conj handle)
                                                       handle))]
                          (server/start! {:host host :port port})))
        instance (start-server! 0)
        port (http/server-port instance)
        connections (atom [])
        first-handle (first @handles)
        first-room (:room-agent first-handle)]
    (try
      (await-tick! first-room 2)
      (is (empty? (get-in @first-room [:room :members])))
      (doseq [_ (range 4)]
        (let [{:keys [events] :as connection} (#'websocket-test/connect! host port)]
          (swap! connections conj connection)
          (#'websocket-test/await-ready! events)
          (#'websocket-test/await-identity! events)))
      (is (= 1 (count @handles)) "four joins must not create four more clocks")
      (await-tick! first-room (+ 160 (get-in @first-room [:room :tick])))
      (is (= 4 (count (get-in @first-room [:room :world :players]))))
      (is (every? #(and (== 264 (:y %)) (false? (:spawn-locked? %)))
                  (vals (get-in @first-room [:room :world :players])))
          "all four players fall and unlock without browser rendering or more input")
      (server/stop! instance)
      (is (.isTerminated ^ScheduledExecutorService (:executor first-handle)))
      (let [stopped-state @first-room
            restarted (start-server! port)
            next-room (:room-agent (second @handles))]
        (try
          (is (not (identical? first-room next-room)))
          (is (empty? (get-in @next-room [:room :members])))
          (await-tick! next-room 2)
          (is (= stopped-state @first-room)
              "the old room must remain stopped while its replacement advances")
          (finally
            (server/stop! restarted))))
      (finally
        (doseq [{:keys [websocket]} @connections]
          (#'websocket-test/abort-websocket! websocket))
        (server/stop! instance)))))

(deftest failed-http-bind-does-not-start-another-clock
  (let [instance (server/start! {:host "127.0.0.1" :port 0})
        starts (atom 0)]
    (try
      (with-redefs [ticker/start! (fn [_] (swap! starts inc))]
        (is (thrown? Exception
                     (server/start! {:host "127.0.0.1"
                                     :port (http/server-port instance)}))))
      (is (zero? @starts))
      (finally
        (server/stop! instance)))))

(deftest ticker-startup-failure-releases-the-bound-http-port
  (let [first-server (server/start! {:host "127.0.0.1" :port 0})
        port (http/server-port first-server)]
    (server/stop! first-server)
    (with-redefs [ticker/start! (fn [_] (throw (ex-info "Test ticker startup failure" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Test ticker startup failure"
                            (server/start! {:host "127.0.0.1" :port port}))))
    (let [rebound (server/start! {:host "127.0.0.1" :port port})]
      (try
        (is (= port (http/server-port rebound)))
        (finally
          (server/stop! rebound))))))

(ns clojure-arena.server-snapshot-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]
            [clojure-arena.server :as server]
            [clojure-arena.ticker :as ticker]
            [clojure-arena.websocket-test :as ws]
            [org.httpkit.server :as http])
  (:import [java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit]))

(defn- host []
  (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1"))

(defn- next-snapshot! [events predicate]
  (let [deadline (+ (System/nanoTime) 4000000000)]
    (loop []
      (let [remaining (max 0 (- deadline (System/nanoTime)))
            [kind text :as event] (.poll ^LinkedBlockingQueue events remaining
                                         TimeUnit/NANOSECONDS)]
        (when-not (= :text kind)
          (throw (ex-info "Expected a snapshot before the deadline" {:event event})))
        (let [message (protocol/decode text)]
          (is (protocol/server-message? message))
          (if (and (= :snapshot (:type message)) (predicate message))
            message
            (recur)))))))

(defn- connect! [connections port]
  (let [{:keys [events] :as connection} (#'ws/connect! (host) port)]
    (swap! connections conj connection)
    (#'ws/await-ready! events)
    (assoc connection :identity (#'ws/await-identity! events))))

(defn- await-room! [room-agent]
  (is (await-for 2000 room-agent))
  (is (nil? (agent-error room-agent))))

(defn- snapshots-at! [connections tick]
  (mapv #(next-snapshot! (:events %) (fn [message] (= tick (:tick message))))
        connections))

(deftest two-and-four-real-clients-receive-the-same-committed-world
  (let [captured (atom nil)
        instance (with-redefs [ticker/start! (fn [room-agent]
                                               (reset! captured room-agent)
                                               nil)]
                   (server/start! {:host (host) :port 0}))
        room-agent @captured
        port (http/server-port instance)
        connections (atom [])]
    (try
      (let [first-two (mapv (fn [_] (connect! connections port)) (range 2))]
        (room/enqueue-room-message! room-agent {:type :tick})
        (await-room! room-agent)
        (let [messages (snapshots-at! first-two 1)]
          (is (apply = messages))
          (is (= (get-in @room-agent [:room :world]) (:world (first messages))))
          (is (= 2 (count (get-in (first messages) [:world :players])))))
        (let [last-two (mapv (fn [_] (connect! connections port)) (range 2))
              accepted (into first-two last-two)
              {:keys [websocket events] :as rejected} (#'ws/connect! (host) port)]
          (swap! connections conj rejected)
          (#'ws/await-ready! events)
          (is (= {:type :room-full} (#'ws/await-admission! events)))
          (room/enqueue-room-message! room-agent {:type :tick})
          (await-room! room-agent)
          (let [messages (snapshots-at! accepted 2)]
            (is (apply = messages))
            (is (= #{:platforms :players} (set (keys (:world (first messages))))))
            (is (= #{:player-1 :player-2 :player-3 :player-4}
                   (set (keys (get-in (first messages) [:world :players])))))
            (is (= (get-in @room-agent [:room :world]) (:world (first messages)))))
          (is (nil? (.poll ^LinkedBlockingQueue events 100 TimeUnit/MILLISECONDS))
              "the rejected connection must receive no shared snapshots")
          (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
          (dotimes [_ 20] (room/enqueue-room-message! room-agent {:type :tick}))
          (await-room! room-agent)
          (snapshots-at! accepted 22)
          (let [{:keys [websocket events]} (first accepted)
                input (assoc protocol/neutral-input :type :input :attack? true)]
            (#'ws/send-text! websocket (pr-str input) true)
            (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
            (await-room! room-agent)
            (room/enqueue-room-message! room-agent {:type :tick})
            (await-room! room-agent)
            (let [messages (snapshots-at! accepted 23)]
              (is (apply = messages))
              (is (= 10 (get-in (first messages)
                                 [:world :players :player-2 :accumulated-percentage])))
              (is (= 1 (get-in (first messages)
                                [:world :players :player-1 :melee-strike-id])))
              (is (every? protocol/snapshot-message? messages))))))
      (finally
        (doseq [{:keys [websocket]} @connections] (#'ws/abort-websocket! websocket))
        (server/stop! instance)))))

(deftest delayed-socket-publication-does-not-stall-or-relabel-the-room
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        progressed (CountDownLatch. 1)
        first-effect (atom nil)
        handle (atom nil)
        original-broadcast server/broadcast-world!
        original-ticker ticker/start!]
    (with-redefs [ticker/start! (fn [room-agent]
                                 (add-watch room-agent ::progress
                                            (fn [_ _ _ state]
                                              (when (>= (get-in state [:room :tick]) 20)
                                                (.countDown progressed))))
                                 (let [ticker (original-ticker room-agent)]
                                   (reset! handle ticker)
                                   ticker))
                  server/broadcast-world!
                  (fn [connections effect]
                    (when (compare-and-set! first-effect nil effect)
                      (.countDown entered)
                      (when-not (.await release 3 TimeUnit/SECONDS)
                        (throw (ex-info "Blocked socket publication timed out" {}))))
                    (original-broadcast connections effect))]
      (let [instance (server/start! {:host (host) :port 0})
            port (http/server-port instance)
            connections (atom [])]
        (try
          (let [first-client (connect! connections port)]
            (is (.await entered 3 TimeUnit/SECONDS))
            (let [second-client (connect! connections port)
                  room-agent (:room-agent @handle)]
              (is (.await progressed 3 TimeUnit/SECONDS)
                  "room ticks and new admissions must continue while delivery is blocked")
              (ticker/stop! @handle)
              (let [committed (:room @room-agent)]
                (is (>= (:tick committed) 20))
                (is (= 2 (count (:members committed))))
                (.countDown release)
                (is (= (:message @first-effect)
                       (next-snapshot! (:events first-client) (constantly true)))
                    "the delayed first snapshot keeps its original world and tick")
                (let [latest (snapshots-at! [first-client second-client] (:tick committed))]
                  (is (apply = latest))
                  (is (= (:world committed) (:world (first latest))))))))
          (finally
            (.countDown release)
            (doseq [{:keys [websocket]} @connections] (#'ws/abort-websocket! websocket))
            (when-let [agent (:room-agent @handle)]
              (remove-watch agent ::progress))
            (server/stop! instance)))))))

(deftest broadcast-uses-only-captured-recipients-and-one-encoded-world
  (let [sent (atom [])
        connections (atom {:first :channel-a :closed nil :second :channel-b
                           :later :not-a-recipient})
        message {:type :snapshot :tick 9 :world (:world room/initial-room)}
        effect {:connection-tokens [:first :closed :second] :message message}]
    (with-redefs [http/send! (fn [channel text] (swap! sent conj [channel text]))]
      (server/broadcast-world! connections effect))
    (is (= [:channel-a :channel-b] (mapv first @sent)))
    (is (identical? (second (first @sent)) (second (second @sent))))
    (is (= message (protocol/decode (second (first @sent)))))))

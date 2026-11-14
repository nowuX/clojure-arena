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

(defn- waiting-spawn-player [color]
  (assoc (room/make-spawn-player color) :lifecycle :waiting :stocks 0))

(defn- await-room! [room-agent]
  (is (await-for 2000 room-agent))
  (is (nil? (agent-error room-agent))))

(defn- enqueue-ticks! [room-agent tick-count]
  (dotimes [_ tick-count]
    (room/enqueue-room-message! room-agent {:type :tick}))
  (await-room! room-agent))

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
              before-rejection (:room @room-agent)
              {:keys [websocket events] :as rejected} (#'ws/connect! (host) port)]
          (swap! connections conj rejected)
          (#'ws/await-ready! events)
          (is (= {:type :room-full} (#'ws/await-admission! events)))
          (#'ws/close-websocket! websocket events)
          (await-room! room-agent)
          (is (= before-rejection (:room @room-agent))
              "rejection and close preserve every admitted player, input, color, and ID counter")
          (room/enqueue-room-message! room-agent {:type :tick})
          (await-room! room-agent)
          (let [messages (snapshots-at! accepted 2)]
            (is (apply = messages))
            (is (= #{:blast-bounds :platforms :players :match}
                   (set (keys (:world (first messages))))))
            (is (= #{:player-1 :player-2 :player-3 :player-4}
                   (set (keys (get-in (first messages) [:world :players])))))
            (is (= (get-in @room-agent [:room :world]) (:world (first messages)))))
          (is (nil? (.poll ^LinkedBlockingQueue events 100 TimeUnit/MILLISECONDS))
              "the rejected connection must receive no shared snapshots")
          ;; Tick 2 has already advanced the countdown twice. 118 more ticks
          ;; start the frozen roster; 40 ticks land players and 80 expire their
          ;; spawn protection before the combat assertion.
          (enqueue-ticks! room-agent 238)
          (let [ready (snapshots-at! accepted 240)
                world (:world (first ready))]
            (is (apply = ready))
            (is (= :running (get-in world [:match :phase])))
            (is (every? #(and (= 3 (:stocks %))
                              (= :active (:lifecycle %))
                              (zero? (:protection-seconds-left %))
                              (false? (:spawn-locked? %)))
                        (vals (:players world)))))
          (let [{:keys [websocket events]} (first accepted)
                input (assoc protocol/neutral-input :type :input :attack? true)]
            (#'ws/send-text! websocket (pr-str input) true)
            (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
            (await-room! room-agent)
            (room/enqueue-room-message! room-agent {:type :tick})
            (await-room! room-agent)
            (let [messages (snapshots-at! accepted 241)]
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

(deftest a-fresh-connection-is-admitted-once-a-slot-is-available
  (let [captured (atom nil)
        instance (with-redefs [ticker/start! (fn [room-agent]
                                               (reset! captured room-agent)
                                               nil)]
                   (server/start! {:host (host) :port 0}))
        room-agent @captured
        port (http/server-port instance)
        connections (atom [])]
    (try
      (let [accepted (mapv (fn [_] (connect! connections port)) (range 4))
            {:keys [websocket events] :as rejected} (#'ws/connect! (host) port)]
        (swap! connections conj rejected)
        (#'ws/await-ready! events)
        (is (= {:type :room-full} (#'ws/await-admission! events)))
        (#'ws/close-websocket! websocket events)
        (await-room! room-agent)
        (is (= 5 (get-in @room-agent [:room :next-player-number])))
        (let [{:keys [websocket events]} (first accepted)]
          (#'ws/close-websocket! websocket events))
        (await-room! room-agent)
        (let [available (:room @room-agent)]
          (is (not (contains? (get-in available [:world :players]) :player-1)))
          (let [retried (connect! connections port)
                after (:room @room-agent)]
            (is (= {:type :identity :player-id :player-5 :color :cyan}
                   (:identity retried)))
            (is (= 4 (count (:members after))
                   (count (get-in after [:world :players]))
                   (count (:inputs-by-player after))))
            (is (= 6 (:next-player-number after)))
            (is (= (waiting-spawn-player :cyan)
                   (get-in after [:world :players :player-5])))
            (is (= protocol/neutral-input
                   (get-in after [:inputs-by-player :player-5])))
            (is (= (get-in available [:world :players])
                   (dissoc (get-in after [:world :players]) :player-5))
                "the new connection must not reset any existing character")
            (is (= (:inputs-by-player available)
                   (dissoc (:inputs-by-player after) :player-5))))))
      (finally
        (doseq [{:keys [websocket]} @connections] (#'ws/abort-websocket! websocket))
        (server/stop! instance)))))

(deftest ring-out-remains-a-connected-player-in-all-four-snapshots
  (let [captured (atom nil)
        instance (with-redefs [ticker/start! (fn [room-agent]
                                               (reset! captured room-agent)
                                               nil)]
                   (server/start! {:host (host) :port 0}))
        room-agent @captured
        port (http/server-port instance)
        connections (atom [])]
    (try
      (let [accepted (mapv (fn [_] (connect! connections port)) (range 4))
            {:keys [websocket events]} (first accepted)]
        (enqueue-ticks! room-agent 119)
        (let [snapshots (snapshots-at! accepted 119)]
          (is (apply = snapshots))
          (is (every? #(and (= 0 (:stocks %))
                            (= :waiting (:lifecycle %)))
                      (vals (get-in (first snapshots) [:world :players])))))
        (enqueue-ticks! room-agent 1)
        (let [started (snapshots-at! accepted 120)
              world (:world (first started))]
          (is (apply = started))
          (is (= :running (get-in world [:match :phase])))
          (is (every? #(and (= 3 (:stocks %)) (:spawn-locked? %))
                      (vals (:players world)))))
        (enqueue-ticks! room-agent 120)
        (let [ready (snapshots-at! accepted 240)
              world (:world (first ready))]
          (is (apply = ready))
          (is (every? #(and (= :active (:lifecycle %))
                            (false? (:spawn-locked? %))
                            (zero? (:protection-seconds-left %)))
                      (vals (:players world)))))
        (#'ws/send-text! websocket
                        (pr-str (assoc protocol/neutral-input :type :input :horizontal 1))
                        true)
        (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
        (await-room! room-agent)
        (enqueue-ticks! room-agent 180)
        (let [snapshots (snapshots-at! accepted 420)
              world (:world (first snapshots))
              absent (get-in world [:players :player-1])]
          (is (apply = snapshots))
          (is (= {:stocks 2 :lifecycle :respawning}
                 (select-keys absent [:stocks :lifecycle])))
          (is (= 2 (get-in world [:match :contenders :player-1 :stocks])))
          (is (every? #(= {:stocks 3 :lifecycle :active}
                         (select-keys % [:stocks :lifecycle]))
                      (vals (dissoc (:players world) :player-1))))
          (is (= 4 (count (get-in @room-agent [:room :members]))
                 (count (:players world))
                 (count (get-in @room-agent [:room :inputs-by-player]))))
          (#'ws/send-text! websocket
                          (pr-str {:type :input :horizontal -1
                                   :jump? true :drop? true :attack? true}) true)
          (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
          (await-room! room-agent)
          (is (= {:horizontal -1 :jump? false :drop? false :attack? false}
                 (get-in @room-agent [:room :inputs-by-player :player-1])))
          (enqueue-ticks! room-agent 10)
          (let [later (snapshots-at! accepted 430)]
            (is (apply = later))
            (let [waiting (get-in (first later) [:world :players :player-1])
                  ticks-left (long (Math/round
                                    (double (/ (:respawn-seconds-left waiting)
                                               room/tick-seconds))))
                  respawn-tick (+ 430 ticks-left)]
              (is (= (dissoc absent :respawn-seconds-left)
                     (dissoc waiting :respawn-seconds-left)))
              (is (< (:respawn-seconds-left waiting) (:respawn-seconds-left absent)))
              (#'ws/send-text! websocket (pr-str (assoc protocol/neutral-input :type :input)) true)
              (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
              (await-room! room-agent)
              (enqueue-ticks! room-agent ticks-left)
              (let [returned (snapshots-at! accepted respawn-tick)
                    player (get-in (first returned) [:world :players :player-1])]
                (is (apply = returned))
                (is (= (-> (room/make-spawn-player :cyan)
                           (assoc :stocks 2 :protection-seconds-left 2))
                       player))
                (is (every? protocol/snapshot-message? returned)))
              (enqueue-ticks! room-agent 80)
              (let [unprotected (snapshots-at! accepted (+ respawn-tick 80))
                    player (get-in (first unprotected) [:world :players :player-1])]
                (is (apply = unprotected))
                (is (= :active (:lifecycle player)))
                (is (= 2 (:stocks player)))
                (is (false? (:spawn-locked? player)))
                (is (zero? (:protection-seconds-left player)))))))
        (let [{:keys [websocket events] :as fifth} (#'ws/connect! (host) port)]
          (swap! connections conj fifth)
          (#'ws/await-ready! events)
          (is (= {:type :room-full} (#'ws/await-admission! events))
              "a ring-out does not release connection capacity")
          (#'ws/close-websocket! websocket events)))
      (finally
        (doseq [{:keys [websocket]} @connections] (#'ws/abort-websocket! websocket))
        (server/stop! instance)))))

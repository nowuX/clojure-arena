(ns clojure-arena.server-match-flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]
            [clojure-arena.server :as server]
            [clojure-arena.server-leave-test :as leave]
            [clojure-arena.server-snapshot-test :as snapshots]
            [clojure-arena.ticker :as ticker]
            [clojure-arena.websocket-test :as ws]
            [org.httpkit.server :as http]))

(defn- host []
  (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1"))

(defn- close-to? [actual expected]
  (<= (Math/abs (- (double actual) (double expected))) 1e-8))

(defn- connect! [connections port]
  (#'snapshots/connect! connections port))

(defn- tick! [room-agent n]
  (dotimes [_ n]
    (room/enqueue-room-message! room-agent {:type :tick}))
  (#'snapshots/await-room! room-agent))

(defn- shared-snapshots! [connections tick-number]
  (let [messages (#'snapshots/snapshots-at! connections tick-number)]
    (is (apply = messages)
        (str "all clients should receive the same world at tick " tick-number))
    (is (every? protocol/snapshot-message? messages))
    messages))

(defn- await-departure! [room-agent player-id]
  (#'leave/await-room-state!
   room-agent
   #(and (not (contains? (get-in % [:room :world :players]) player-id))
         (true? (get-in % [:room :world :match :contenders player-id
                           :forfeited?])))))

(deftest real-websockets-share-a-forfeit-result-and-complete-a-draw-rematch
  (let [captured-room (atom nil)
        instance (with-redefs [ticker/start! (fn [room-agent]
                                               (reset! captured-room room-agent)
                                               nil)]
                   (server/start! {:host (host) :port 0}))
        room-agent @captured-room
        port (http/server-port instance)
        connections (atom [])]
    (try
      (let [player-a (connect! connections port)
            player-b (connect! connections port)]
        (tick! room-agent 120)
        (let [started (shared-snapshots! [player-a player-b] 120)
              world (:world (first started))]
          (is (= :running (get-in world [:match :phase])))
          (is (= 1 (get-in world [:match :id])))
          (is (= 180 (get-in world [:match :time-seconds-left])))
          (is (= #{:player-1 :player-2}
                 (set (keys (get-in world [:match :contenders]))))))
        (let [observer-a (connect! connections port)
              observer-b (connect! connections port)]
          (is (= {:type :identity :player-id :player-3 :color :amber}
                 (:identity observer-a)))
          (is (= {:type :identity :player-id :player-4 :color :green}
                 (:identity observer-b)))
          (tick! room-agent 1)
          (let [observed (shared-snapshots! [player-a player-b observer-a observer-b] 121)
                world (:world (first observed))]
            (is (= #{:player-1 :player-2}
                   (set (keys (get-in world [:match :contenders])))))
            (doseq [player-id [:player-3 :player-4]]
              (is (= [:waiting 0]
                     ((juxt :lifecycle :stocks) (get-in world [:players player-id]))))))
          (testing "a real socket departure forfeits one contender in the next shared snapshot"
            (#'ws/close-websocket! (:websocket player-a) (:events player-a))
            (is (await-departure! room-agent :player-1))
            (is (= 5 (get-in @room-agent [:room :world :match
                                         :intermission-seconds-left])))
            (tick! room-agent 1)
            (let [finished (shared-snapshots! [player-b observer-a observer-b] 122)
                  world (:world (first finished))
                  result (get-in world [:match :result])]
              (is (= :finished (get-in world [:match :phase])))
              (is (= [:last-contender :player-2]
                     ((juxt :reason :winner-id) result)))
              (is (true? (get-in world [:match :contenders :player-1 :forfeited?])))
              (is (nil? (get-in world [:players :player-1])))
              (is (= :waiting (get-in world [:players :player-3 :lifecycle])))
              (is (every? protocol/snapshot-message? finished)))
          (let [departed-observer (:identity observer-a)]
            (#'ws/close-websocket! (:websocket observer-a) (:events observer-a))
            (is (#'leave/await-room-state!
                 room-agent
                 #(not (contains? (get-in % [:room :world :players])
                                  (:player-id departed-observer)))))
            (testing "the full intermission preserves the result, then resets current members"
              (tick! room-agent 198)
              (let [before-reset (shared-snapshots! [player-b observer-b] 320)
                    world (:world (first before-reset))]
                (is (= :finished (get-in world [:match :phase])))
                (is (= :last-contender (get-in world [:match :result :reason])))
                (is (close-to? (get-in world [:match :intermission-seconds-left])
                               0.025))
                (tick! room-agent 1)
                (let [reset (shared-snapshots! [player-b observer-b] 321)
                      world (:world (first reset))
                      room-state (:room @room-agent)]
                  (is (= :countdown (get-in world [:match :phase])))
                  (is (= 1 (get-in world [:match :id])))
                  (is (= 3 (get-in world [:match :countdown-seconds-left])))
                  (is (nil? (get-in world [:match :result])))
                  (is (empty? (get-in world [:match :contenders])))
                  (is (= #{:player-2 :player-4}
                         (set (keys (:players world)))))
                  (is (= #{:player-2 :player-4}
                         (set (keys (:inputs-by-player room-state)))))
                  (is (every? #(= [:waiting 0]
                                  ((juxt :lifecycle :stocks) %))
                              (vals (:players world)))))
                (tick! room-agent 119)
                (let [boundary (shared-snapshots! [player-b observer-b] 440)]
                  (is (= :countdown (get-in (first boundary) [:world :match :phase])))
                  (is (close-to? (get-in (first boundary) [:world :match
                                                           :countdown-seconds-left])
                                 0.025)))
                (tick! room-agent 1)
                (let [rematch (shared-snapshots! [player-b observer-b] 441)
                      world (:world (first rematch))]
                  (is (= :running (get-in world [:match :phase])))
                  (is (= 2 (get-in world [:match :id])))
                  (is (= 180 (get-in world [:match :time-seconds-left])))
                  (is (= #{:player-2 :player-4}
                         (set (keys (get-in world [:match :contenders])))))
                  (is (every? #(= 2 (:protection-seconds-left %))
                              (vals (:players world))))
                  (testing "the next tied time limit is an immutable shared draw"
                    (send room-agent assoc-in
                          [:room :world :match :time-seconds-left] 0.025)
                    (#'snapshots/await-room! room-agent)
                    (tick! room-agent 1)
                    (let [draw (shared-snapshots! [player-b observer-b] 442)
                          draw-world (:world (first draw))
                          result (get-in draw-world [:match :result])]
                      (is (= :finished (get-in draw-world [:match :phase])))
                      (is (= :time-limit (:reason result)))
                      (is (nil? (:winner-id result)))
                      (is (= 2 (count (:standings result))))
                      (is (= 5 (get-in draw-world [:match
                                                   :intermission-seconds-left])))
                      (tick! room-agent 1)
                      (let [after-draw (shared-snapshots! [player-b observer-b] 443)
                            next-world (:world (first after-draw))]
                        (is (= result (get-in next-world [:match :result])))
                        (is (close-to? (get-in next-world [:match
                                                           :intermission-seconds-left])
                                       4.975))))))))))))
      (finally
        (doseq [{:keys [websocket]} @connections]
          (#'ws/abort-websocket! websocket))
        (server/stop! instance)))))

(deftest real-websockets-share-stock-loss-respawn-protection-and-elimination
  (let [captured (atom nil)
        instance (with-redefs [ticker/start! (fn [room-agent] (reset! captured room-agent) nil)]
                   (server/start! {:host (host) :port 0}))
        room-agent @captured
        connections (atom [])
        exit! (fn []
                (send room-agent
                      (fn [envelope]
                        (-> envelope (assoc :effects [])
                            (assoc-in [:room :world :players :player-1 :x] 900))))
                (#'snapshots/await-room! room-agent)
                (tick! room-agent 1))]
    (try
      (let [clients [(connect! connections (http/server-port instance))
                     (connect! connections (http/server-port instance))]]
        (tick! room-agent 120)
        (shared-snapshots! clients 120)
        (exit!)
        (let [loss (:world (first (shared-snapshots! clients 121)))]
          (is (= [2 :respawning 3]
                 ((juxt :stocks :lifecycle :respawn-seconds-left)
                  (get-in loss [:players :player-1])))))
        (tick! room-agent 119)
        (let [before (:world (first (shared-snapshots! clients 240)))]
          (is (= :respawning (get-in before [:players :player-1 :lifecycle]))))
        (tick! room-agent 1)
        (let [returned (:world (first (shared-snapshots! clients 241)))
              player (get-in returned [:players :player-1])]
          (is (= [2 :active 0 2] ((juxt :stocks :lifecycle :accumulated-percentage :protection-seconds-left) player)))
          (is (= [340 160] ((juxt :x :y) player)))
          (is (:spawn-locked? player)))
        (tick! room-agent 79)
        (let [protected (:world (first (shared-snapshots! clients 320)))]
          (is (pos? (get-in protected [:players :player-1 :protection-seconds-left]))))
        (tick! room-agent 1)
        (let [expired (:world (first (shared-snapshots! clients 321)))]
          (is (zero? (get-in expired [:players :player-1 :protection-seconds-left]))))
        (exit!)
        (let [second-loss (:world (first (shared-snapshots! clients 322)))]
          (is (= 1 (get-in second-loss [:players :player-1 :stocks]))))
        (tick! room-agent 120)
        (shared-snapshots! clients 442)
        (exit!)
        (let [finished (:world (first (shared-snapshots! clients 443)))]
          (is (= [0 :eliminated] ((juxt :stocks :lifecycle) (get-in finished [:players :player-1]))))
          (is (= :finished (get-in finished [:match :phase])))
          (is (= [:last-contender :player-2] ((juxt :reason :winner-id) (get-in finished [:match :result]))))
          (is (= 2 (count (:players finished))))
          (is (= 2 (count (get-in @room-agent [:room :members]))))))
      (finally
        (doseq [{:keys [websocket]} @connections] (#'ws/abort-websocket! websocket))
        (server/stop! instance)))))

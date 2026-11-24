(ns clojure-arena.room-tick-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]))

(defn- join-room [state connection-token]
  (room/apply-room-message state
                           {:type :join
                            :connection-token connection-token}))

(defn- set-input [state connection-token input]
  (:room (room/apply-room-message state
                                  {:type :input
                                   :connection-token connection-token
                                   :input input})))

(defn- tick-room [state]
  (room/apply-room-message state {:type :tick}))

(defn- prepare-waiting-players-for-physics-fixture [room-state]
  (update-in room-state [:world :players]
             (fn [players]
               (into {}
                     (map (fn [[player-id player]]
                            [player-id (if (= :waiting (:lifecycle player))
                                         (assoc player :lifecycle :active :stocks 3)
                                         player)]))
                     players))))

(defn- clear-actions [inputs-by-player]
  (into {}
        (map (fn [[player-id input]]
               [player-id (assoc input
                                 :jump? false
                                 :drop? false
                                 :attack? false)]))
        inputs-by-player))

(defn- manual-tick [state]
  (let [inputs (:inputs-by-player state)
        world (match/step (:world state) inputs room/tick-seconds)]
    (-> state
        (assoc :world world)
        (update :tick inc)
        (update :inputs-by-player clear-actions))))

(defn- await-agent! [room-agent]
  (let [finished? (await-for 2000 room-agent)]
    (is finished?
        "room agent should finish its queued actions before the deadline")
  (is (nil? (agent-error room-agent))
        "room actions should not fail the agent")
    finished?))

(deftest fixed-ticks-match-a-manual-game-step-timeline-test
  (let [joined-a (:room (join-room room/initial-room :connection-a))
        joined-b (:room (join-room joined-a :connection-b))
        prepared (set-input
                  (set-input (match-helpers/running-room
                              (prepare-waiting-players-for-physics-fixture joined-b))
                             :connection-a
                             (assoc protocol/neutral-input :horizontal 1 :jump? true))
                  :connection-b
                  (assoc protocol/neutral-input :horizontal -1 :attack? true))
        expected (nth (iterate manual-tick prepared) 12)
        actual (nth (iterate (comp :room tick-room) prepared) 12)]
    (is (= 0 (:tick room/initial-room)))
    (is (= 0.025 room/tick-seconds))
    (is (= expected actual))
    (is (= 12 (:tick actual)))
    (is (= (assoc protocol/neutral-input :horizontal 1)
           (get-in actual [:inputs-by-player :player-1])))
    (is (= (assoc protocol/neutral-input :horizontal -1)
           (get-in actual [:inputs-by-player :player-2])))))

(deftest tick-steps-the-world-once-with-all-inputs-and-the-fixed-delta-test
  (let [joined (match-helpers/running-room
                (prepare-waiting-players-for-physics-fixture
                 (:room (join-room room/initial-room :connection-a))))
        prepared (set-input joined :connection-a
                            (assoc protocol/neutral-input :horizontal 1 :jump? true))
        world (:world prepared)
        inputs (:inputs-by-player prepared)
        original-step game/step
        calls (atom [])]
    (with-redefs [game/step (fn [received-world received-inputs dt]
                              (swap! calls conj [received-world received-inputs dt])
                              (original-step received-world received-inputs dt))]
      (tick-room prepared))
    (is (= 1 (count @calls)))
    (is (identical? world (first (first @calls))))
    (is (identical? inputs (second (first @calls))))
    (is (= room/tick-seconds (nth (first @calls) 2)))))

(deftest empty-room-ticks-advance-the-counter-and-freeze-empty-worlds-test
  (let [first-result (tick-room room/initial-room)
        second-result (tick-room (:room first-result))]
    (is (= {:room (assoc room/initial-room :tick 1)
            :effects []}
           first-result))
    (is (identical? (:world room/initial-room)
                    (get-in first-result [:room :world]))
        "waiting rooms freeze the exact world fixture")
    (is (= 2 (get-in second-result [:room :tick]))
        "empty rooms should keep accepting pure ticks")
    (is (= {} (get-in second-result [:room :world :players])))))

(deftest jump-drop-and-attack-are-consumed-once-across-ticks-test
  (let [joined (reduce (fn [state token]
                         (:room (join-room state token)))
                       room/initial-room
                       [:jump :drop :attacker :target])
        initial-players
        {:player-1 (assoc (game/make-player "#22d3ee" 80 100)
                          :spawn-locked? false)
         :player-2 (assoc (game/make-player "#e11d48" 260 194)
                          :spawn-locked? false)
         :player-3 (assoc (game/make-player "#f59e0b" 350 80)
                          :spawn-locked? false)
         :player-4 (assoc (game/make-player "#4ade80" 380 80)
                          :spawn-locked? false)}
        prepared (-> joined
                     (assoc-in [:world :players] initial-players)
                     match-helpers/running-room
                     (set-input :jump (assoc protocol/neutral-input :jump? true))
                     (set-input :drop (assoc protocol/neutral-input :drop? true))
                     (set-input :attacker (assoc protocol/neutral-input :attack? true)))
        first-tick (:room (tick-room prepared))
        second-tick (:room (tick-room first-tick))]
    (testing "the first tick consumes the three pending actions"
      (is (= 0 (get-in first-tick [:world :players :player-1 :air-jumps-left])))
      (is (= :left (get-in first-tick [:world :players :player-2
                                       :drop-through-platform-id])))
      (is (= 1 (get-in first-tick [:world :players :player-3 :melee-strike-id])))
      (is (= 10 (get-in first-tick [:world :players :player-4
                                    :accumulated-percentage])))
      (is (= protocol/neutral-input
             (get-in first-tick [:inputs-by-player :player-1])))
      (is (= protocol/neutral-input
             (get-in first-tick [:inputs-by-player :player-2])))
      (is (= protocol/neutral-input
             (get-in first-tick [:inputs-by-player :player-3]))))
    (testing "later ticks do not repeat those actions"
      (is (= 0 (get-in second-tick [:world :players :player-1 :air-jumps-left])))
      (is (= 1 (get-in second-tick [:world :players :player-3 :melee-strike-id])))
      (is (= 10 (get-in second-tick [:world :players :player-4
                                     :accumulated-percentage]))))))

(deftest held-horizontal-input-persists-between-ticks-test
  (let [joined-a (:room (join-room room/initial-room :connection-a))
        joined (prepare-waiting-players-for-physics-fixture
                (:room (join-room joined-a :connection-b)))
        active-player (-> (get-in joined [:world :players :player-1])
                          (assoc :x 300 :y 264 :spawn-locked? false
                                 :lifecycle :active :stocks 3))
        prepared (-> joined
                     (assoc-in [:world :players :player-1] active-player)
                     match-helpers/running-room
                     (set-input :connection-a (assoc protocol/neutral-input :horizontal 1)))
        final-room (nth (iterate (comp :room tick-room) prepared) 4)
        player (get-in final-room [:world :players :player-1])]
    (is (= 1 (get-in final-room [:inputs-by-player :player-1 :horizontal])))
    (is (pos? (:vx player)))
    (is (> (:x player) 300))))

(deftest spawn-lock-controls-and-room-ticks-integrate-with-landing-test
  (let [joined-a (:room (join-room room/initial-room :connection-a))
        joined (prepare-waiting-players-for-physics-fixture
                (:room (join-room joined-a :connection-b)))
        spawn-x (get-in joined [:world :players :player-1 :x])
        landing-player (assoc (get-in joined [:world :players :player-1])
                              :y 258
                              :vy 240
                              :air-jumps-left 0
                              :lifecycle :active
                              :stocks 3)
        prepared (-> joined
                     (assoc-in [:world :players :player-1] landing-player)
                     match-helpers/running-room
                     (set-input :connection-a (assoc protocol/neutral-input :horizontal 1 :jump? true :drop? true :attack? true)))
        landed (:room (tick-room prepared))
        landed-player (get-in landed [:world :players :player-1])
        moved (:room (tick-room landed))]
    (is (false? (:spawn-locked? landed-player)))
    (is (== 264 (:y landed-player)))
    (is (= 1 (:air-jumps-left landed-player)))
    (is (== spawn-x (:x landed-player))
        "the spawn lock should ignore held direction during the landing tick")
    (is (= 0 (:melee-strike-id landed-player)))
    (is (nil? (:drop-through-platform-id landed-player)))
    (is (= (assoc protocol/neutral-input :horizontal 1)
           (get-in landed [:inputs-by-player :player-1])))
    (is (> (get-in moved [:world :players :player-1 :x]) spawn-x)
        "the retained horizontal direction should take effect after landing")))

(deftest real-melee-tick-applies-impulse-and-reuses-platform-snapshot-test
  (let [joined (reduce (fn [state token]
                         (:room (join-room state token)))
                       room/initial-room
                       [:attacker :target])
        players {:player-1 (assoc (game/make-player "#22d3ee" 350 264)
                                 :spawn-locked? false)
                 :player-2 (assoc (game/make-player "#e11d48" 380 264)
                                 :spawn-locked? false)}
        prepared (-> joined
                     (assoc-in [:world :players] players)
                     match-helpers/running-room
                     (set-input :attacker (assoc protocol/neutral-input :attack? true)))
        original-world (:world prepared)
        platforms (:platforms original-world)
        result (tick-room prepared)
        next-world (get-in result [:room :world])
        target (get-in next-world [:players :player-2])]
    (is (= 10 (:accumulated-percentage target)))
    (is (pos? (:knockback-vx target)))
    (is (pos? (:vx target)))
    (is (= 0 (get-in original-world [:players :player-2 :accumulated-percentage])))
    (is (identical? platforms (:platforms next-world))
        "a room tick should keep sharing the immutable platform snapshot")))

(deftest semisolid-drop-through-works-through-the-room-tick-test
  (let [joined (:room (join-room room/initial-room :connection-a))
        player (assoc (game/make-player "#22d3ee" 260 194)
                      :spawn-locked? false)
        prepared (-> joined
                     (assoc-in [:world :players :player-1] player)
                     match-helpers/running-room
                     (set-input :connection-a (assoc protocol/neutral-input :drop? true)))
        result (tick-room prepared)
        player-after (get-in result [:room :world :players :player-1])]
    (is (= :left (:drop-through-platform-id player-after)))
    (is (> (:y player-after) 194)
        "the player should pass down through the semisolid platform")
    (is (= false (get-in result [:room :inputs-by-player :player-1 :drop?])))))

(deftest same-agent-tick-input-and-join-actions-commit-in-enqueue-order-test
  (let [room-agent (room/make-room (fn [_] nil))
        pending-input (assoc protocol/neutral-input :horizontal 1 :jump? true)]
    (try
      (room/enqueue-room-message! room-agent
                                  {:type :join :connection-token :connection-a})
      (when (await-agent! room-agent)
        (room/enqueue-room-message! room-agent {:type :tick})
        (room/enqueue-room-message! room-agent
                                    {:type :input
                                     :connection-token :connection-a
                                     :input pending-input})
        (room/enqueue-room-message! room-agent
                                    {:type :join :connection-token :connection-b})
        (when (await-agent! room-agent)
          (let [{:keys [room]} @room-agent]
            (is (= 1 (:tick room)))
            (is (= #{:connection-a :connection-b}
                   (set (keys (:members room)))))
            (is (= (assoc protocol/neutral-input :horizontal 1)
                   (get-in room [:inputs-by-player :player-1]))
                "waiting players retain direction and cannot queue action flags")
            (is (= protocol/neutral-input
                   (get-in room [:inputs-by-player :player-2])))
            (is (== 160 (get-in room [:world :players :player-2 :y]))
                "the later join should add its player after the tick ran")
            (is (= 0 (get-in room [:world :players :player-2
                                   :melee-strike-id]))))))
      (finally
        (await-for 2000 room-agent)))))

(deftest invalid-tick-shapes-and-unsupported-internal-messages-are-ignored-test
  (let [joined (:room (join-room room/initial-room :connection-a))
        messages [nil
                  {}
                  {:type :tick :extra true}
                  {:type :tick :connection-token :connection-a}
                  {:type :tick :input {}}
                  {:type :leave}
                  {:type :leave :connection-token :connection-a :extra true}
                  {:type :disconnect :connection-token :connection-a}
                  {:type :snapshot}]]
    (doseq [message messages]
      (let [result (room/apply-room-message joined message)]
        (is (identical? joined (:room result))
            (str "unsupported internal messages should preserve the room for "
                 (pr-str message)))
        (is (= {:room joined :effects []} result)
            (str "unsupported internal messages should have no effects for "
                 (pr-str message)))))))

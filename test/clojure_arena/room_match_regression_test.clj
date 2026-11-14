(ns clojure-arena.room-match-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.match :as match]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- apply-room [state message]
  (:room (room/apply-room-message state message)))

(defn- join [state token]
  (apply-room state {:type :join :connection-token token}))

(defn- leave [state token]
  (apply-room state {:type :leave :connection-token token}))

(defn- tick [state]
  (apply-room state {:type :tick}))

(defn- ticks [state n]
  (nth (iterate tick state) n))

(defn- join-many [tokens]
  (reduce join room/initial-room tokens))

(defn- set-player-state [state player-id fields]
  (-> state
      (update-in [:world :players player-id] merge fields)
      (update-in [:world :match :contenders player-id]
                 merge (select-keys fields [:stocks :accumulated-percentage]))))

(defn- member-players [state]
  (into {}
        (map (fn [[_ {:keys [player-id color]}]]
               [player-id (-> (room/make-spawn-player color)
                              (assoc :lifecycle :waiting :stocks 0))]))
        (:members state)))

(defn- close-to? [actual expected]
  (<= (Math/abs (- (double actual) (double expected))) 1e-8))

(defn- await-room-agent! [room-agent]
  (is (await-for 4000 room-agent) "queued room actions should settle")
  (is (nil? (agent-error room-agent)) "the room agent should remain healthy"))

(deftest concurrent-agent-admissions-produce-one-shared-snapshot
  (let [delivered (atom [])
        room-agent (room/make-room #(swap! delivered conj %))
        tokens [:connection-a :connection-b :connection-c :connection-d]
        ready (CountDownLatch. (count tokens))
        release (CountDownLatch. 1)
        workers (mapv (fn [token]
                        (future
                          (.countDown ready)
                          (.await release)
                          (room/enqueue-room-message!
                           room-agent {:type :join :connection-token token})))
                      tokens)]
    (try
      (is (.await ready 2 TimeUnit/SECONDS) "all callers should reach the barrier")
      (.countDown release)
      (doseq [worker workers]
        (is (not= ::timeout (deref worker 4000 ::timeout))))
      (await-room-agent! room-agent)
      (room/enqueue-room-message! room-agent {:type :tick})
      (await-room-agent! room-agent)
      (let [state (:room @room-agent)
            members (:members state)
            ids (set (map :player-id (vals members)))
            effect (first (peek @delivered))
            snapshot (:message effect)]
        (is (= (set tokens) (set (keys members))))
        (is (= #{:player-1 :player-2 :player-3 :player-4} ids))
        (is (= ids (set (keys (get-in state [:world :players])))))
        (is (= #{:cyan :crimson :amber :green} (set (map :color (vals members)))))
        (is (= (set tokens) (set (:connection-tokens effect))))
        (is (= (:world state) (:world snapshot)))
        (is (protocol/snapshot-message? snapshot))
        (is (= (protocol/decode (pr-str snapshot)) snapshot)))
      (finally
        (.countDown release)
        (await-for 4000 room-agent)))))

(deftest snapshots-observers-forfeits-and-current-rematch-membership
  (let [two-entrants (ticks (join-many [:connection-a :connection-b]) 120)
        running-snapshot {:type :snapshot :tick (:tick two-entrants)
                          :world (:world two-entrants)}
        with-observers (-> two-entrants
                           (join :connection-c)
                           (join :connection-d))
        observer-d-input
        (:room (room/apply-room-message
                with-observers
                {:type :input :connection-token :connection-d
                 :input {:horizontal 1 :jump? true :drop? true :attack? true}}))
        ready-to-forfeit (set-player-state observer-d-input :player-1
                                           {:stocks 2 :accumulated-percentage 37})
        forfeited (leave ready-to-forfeit :connection-a)
        observer-left (leave forfeited :connection-c)
        before-reset (ticks observer-left 199)
        reset-room (tick before-reset)
        before-rematch (ticks reset-room 119)
        rematch (tick before-rematch)
        result (get-in forfeited [:world :match :result])]
    (testing "countdown expiry publishes a complete, wire-valid running world"
      (is (= :countdown (get-in (ticks (join-many [:connection-a :connection-b]) 119)
                                [:world :match :phase])))
      (is (= :running (get-in two-entrants [:world :match :phase])))
      (is (= 1 (get-in two-entrants [:world :match :id])))
      (is (= 180 (get-in two-entrants [:world :match :time-seconds-left])))
      (is (protocol/snapshot-message? running-snapshot)))
    (testing "late arrivals own snapshots but cannot enter the frozen contender roster"
      (is (= #{:player-1 :player-2}
             (set (keys (get-in with-observers [:world :match :contenders])))))
      (is (= :waiting (get-in with-observers [:world :players :player-3 :lifecycle])))
      (is (= 0 (get-in with-observers [:world :players :player-4 :stocks])))
      (is (= :green (get-in with-observers [:members :connection-d :color])))
      (is (= {:horizontal 1 :jump? false :drop? false :attack? false}
             (get-in observer-d-input [:inputs-by-player :player-4])))
      (is (protocol/snapshot-message?
           {:type :snapshot :tick 120 :world (:world observer-d-input)})))
    (testing "a departing contender forfeits captured stats and decides the result"
      (is (= :finished (get-in forfeited [:world :match :phase])))
      (is (= [:last-contender :player-2]
             ((juxt :reason :winner-id) result)))
      (is (= {:color "#22d3ee" :stocks 2 :accumulated-percentage 37
              :forfeited? true}
             (get-in forfeited [:world :match :contenders :player-1])))
      (is (nil? (get-in forfeited [:world :players :player-1])))
      (is (= result (get-in observer-left [:world :match :result])))
      (is (not (contains? (get-in observer-left [:world :match :contenders])
                          :player-3)))
      (is (protocol/snapshot-message?
           {:type :snapshot :tick 120 :world (:world observer-left)})))
    (testing "intermission reset includes only currently connected members"
      (is (= :finished (get-in before-reset [:world :match :phase])))
      (is (= result (get-in before-reset [:world :match :result])))
      (is (= 1 (get-in before-reset [:world :match :id])))
      (is (close-to? (get-in before-reset [:world :match :intermission-seconds-left])
                     0.025))
      (is (= 320 (:tick reset-room)))
      (is (= :countdown (get-in reset-room [:world :match :phase])))
      (is (= 1 (get-in reset-room [:world :match :id])))
      (is (= 3 (get-in reset-room [:world :match :countdown-seconds-left])))
      (is (= #{:player-2 :player-4}
             (set (keys (get-in reset-room [:world :players])))))
      (is (= (member-players reset-room) (get-in reset-room [:world :players])))
      (is (= (:members observer-left) (:members reset-room)))
      (is (empty? (get-in reset-room [:world :match :contenders])))
      (is (nil? (get-in reset-room [:world :match :result])))
      (is (protocol/snapshot-message?
           {:type :snapshot :tick 320 :world (:world reset-room)})))
    (testing "the rematch starts with fresh protection and a new match ID"
      (is (= :countdown (get-in before-rematch [:world :match :phase])))
      (is (< 0.024 (get-in before-rematch [:world :match :countdown-seconds-left])
             0.026))
      (is (= :running (get-in rematch [:world :match :phase])))
      (is (= 2 (get-in rematch [:world :match :id])))
      (is (= #{:player-2 :player-4}
             (set (keys (get-in rematch [:world :match :contenders])))))
      (doseq [[player-id player] (get-in rematch [:world :players])]
        (is (= 2 (:protection-seconds-left player)))
        (is (= (:spawn-x player) (:x player)))
        (is (= (:spawn-y player) (:y player))))
      (is (protocol/snapshot-message?
           {:type :snapshot :tick 440 :world (:world rematch)})))))

(deftest time-limit-ranks-stocks-before-percentage-and-draws-exact-ties
  (let [contenders {:player-1 {:color "#22d3ee" :stocks 2
                               :accumulated-percentage 10 :forfeited? false}
                    :player-2 {:color "#e11d48" :stocks 3
                               :accumulated-percentage 90 :forfeited? false}}
        winner (match/result-for contenders 0)
        tied (match/result-for
              (assoc contenders
                     :player-1 {:color "#22d3ee" :stocks 2
                                :accumulated-percentage 10 :forfeited? false}
                     :player-2 {:color "#e11d48" :stocks 2
                                :accumulated-percentage 10 :forfeited? false})
              0)]
    (is (= [:time-limit :player-2 "#e11d48"]
           ((juxt :reason :winner-id :winner-color) winner)))
    (is (= :time-limit (:reason tied)))
    (is (nil? (:winner-id tied)))))

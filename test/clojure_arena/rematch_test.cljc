(ns clojure-arena.rematch-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-test-helpers :as match-test-helpers]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def tick-seconds 0.025)

(defn- stale-player [color x fields]
  (merge (game/make-player color x 160)
         {:x 701 :y -120 :vx 31 :vy -47
          :knockback-vx 83 :knockback-vy -61
          :facing :left :spawn-locked? false :air-jumps-left 0
          :drop-through-platform-id :right
          :melee-cooldown-left 0.2 :melee-strike-id 9
          :melee-resolved-strike-id 4 :melee-target-ids #{:player-2}}
         fields))

(defn- stale-running-world []
  (match-test-helpers/running-world
   (assoc game/initial-world
          :players
          {:player-1 (stale-player "#22d3ee" 340
                                   {:lifecycle :active :stocks 2
                                    :accumulated-percentage 45})
           :player-2 (stale-player "#e11d48" 380
                                   {:lifecycle :active :stocks 3
                                    :accumulated-percentage 7})
           :player-3 (stale-player "#f59e0b" 420
                                   {:lifecycle :eliminated :stocks 0
                                    :accumulated-percentage 86})
           :player-4 (stale-player "#4ade80" 460
                                   {:lifecycle :waiting :stocks 0
                                    :respawn-seconds-left 0
                                    :protection-seconds-left 0
                                    :accumulated-percentage 0})})))

(defn- finish-world [world]
  (let [contenders (get-in world [:match :contenders])]
    (-> world
        (assoc-in [:match :phase] :finished)
        (assoc-in [:match :time-seconds-left] 0)
        (assoc-in [:match :intermission-seconds-left] match/intermission-duration)
        (assoc-in [:match :result] (match/result-for contenders 0)))))

(defn- advance-ticks [world tick-count inputs]
  (loop [current world
         remaining tick-count]
    (if (zero? remaining)
      current
      (recur (match/step current inputs tick-seconds) (dec remaining)))))

(defn- factory-waiting-player [player]
  (assoc (game/make-player (:color player) (:spawn-x player) (:spawn-y player))
         :lifecycle :waiting
         :stocks 0))

(defn- close-to? [actual expected]
  #?(:clj (<= (Math/abs (- actual expected)) 1e-8)
     :cljs (<= (js/Math.abs (- actual expected)) 1e-8)))

(deftest intermission-reset-is-exactly-on-the-200th-tick-test
  (let [finished (finish-world (stale-running-world))
        result (get-in finished [:match :result])
        held-inputs {:player-1 {:horizontal 1 :jump? true :drop? true :attack? true}}
        after-199 (advance-ticks finished 199 held-inputs)
        reset (match/step after-199 held-inputs tick-seconds)
        player-ids (set (keys (:players finished)))]
    (testing "the result and all player state remain unchanged through tick 199"
      (is (= :finished (get-in after-199 [:match :phase])))
      (is (close-to? (get-in after-199 [:match :intermission-seconds-left])
                     tick-seconds))
      (is (= result (get-in after-199 [:match :result])))
      (is (= finished (assoc-in after-199 [:match :intermission-seconds-left]
                                match/intermission-duration)))
      (is (= (get-in finished [:players :player-4])
             (get-in after-199 [:players :player-4]))
          "a waiting observer stays frozen until the intermission expires"))
    (testing "tick 200 resets every current player through the factory"
      (is (= :countdown (get-in reset [:match :phase])))
      (is (= 1 (get-in reset [:match :id]))
          "the completed match ID remains during the new countdown")
      (is (= match/countdown-duration
             (get-in reset [:match :countdown-seconds-left]))
          "reset starts a full countdown without consuming its first tick")
      (is (= 0 (get-in reset [:match :time-seconds-left])))
      (is (= 0 (get-in reset [:match :intermission-seconds-left])))
      (is (nil? (get-in reset [:match :result])))
      (is (= {} (get-in reset [:match :contenders])))
      (is (= player-ids (set (keys (:players reset)))))
      (doseq [player-id player-ids]
        (let [old-player (get-in finished [:players player-id])
              reset-player (get-in reset [:players player-id])]
          (is (= (factory-waiting-player old-player) reset-player)
              (str "player " player-id " has fresh spawn, combat, and timer state"))
          (is (= [0 0 0 0 0]
                 ((juxt :stocks :respawn-seconds-left :protection-seconds-left
                        :melee-strike-id :melee-cooldown-left)
                  reset-player))))))))

(deftest rematch-countdown-and-match-ids-repeat-for-observers-and-eliminated-players-test
  (let [first-finished (finish-world (stale-running-world))
        first-reset (advance-ticks first-finished 200 {})
        before-first-start (advance-ticks first-reset 119 {})
        first-running (match/step before-first-start {} tick-seconds)
        second-finished (finish-world first-running)
        second-reset (advance-ticks second-finished 200 {})
        before-second-start (advance-ticks second-reset 119 {})
        second-running (match/step before-second-start {} tick-seconds)]
    (testing "former observers and eliminated players enter the next match roster"
      (is (= #{:player-1 :player-2 :player-3 :player-4}
             (set (keys (get-in first-running [:match :contenders])))))
      (is (= :running (get-in first-running [:match :phase])))
      (is (= 2 (get-in first-running [:match :id])))
      (is (close-to? (get-in before-first-start
                             [:match :countdown-seconds-left])
                     tick-seconds))
      (is (= match/duration (get-in first-running [:match :time-seconds-left])))
      (is (every? #(= 3 (:stocks %)) (vals (:players first-running))))
      (is (every? #(= game/spawn-protection-duration
                       (:protection-seconds-left %))
                  (vals (:players first-running)))))
    (testing "a second intermission retains ID 2 through reset and starts ID 3"
      (is (= :countdown (get-in second-reset [:match :phase])))
      (is (= 2 (get-in second-reset [:match :id])))
      (is (= match/countdown-duration
             (get-in second-reset [:match :countdown-seconds-left])))
      (is (= :running (get-in second-running [:match :phase])))
      (is (= 3 (get-in second-running [:match :id])))
      (is (= match/duration (get-in second-running [:match :time-seconds-left]))))))

(deftest fewer-than-two-players-wait-after-intermission-until-another-join-test
  (let [finished (finish-world (stale-running-world))
        one-player (-> finished
                       (match/remove-player :player-2)
                       (match/remove-player :player-3)
                       (match/remove-player :player-4))
        reset (advance-ticks one-player 200 {})
        joined (match/add-player reset :player-5
                                 (game/make-player "#4ade80" 460 160))]
    (is (= 1 (count (:players reset))))
    (is (= :waiting (get-in reset [:match :phase])))
    (is (= 1 (get-in reset [:match :id])))
    (is (= 0 (get-in reset [:match :countdown-seconds-left])))
    (is (= 0 (get-in reset [:match :intermission-seconds-left])))
    (is (nil? (get-in reset [:match :result])))
    (is (= :countdown (get-in joined [:match :phase])))
    (is (= match/countdown-duration
           (get-in joined [:match :countdown-seconds-left])))))

(ns clojure-arena.spawn-protection-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def bounds {:left -1000 :right 1000 :top -500 :bottom 500})
(def floor {:id :floor :type :solid :x 0 :y 100 :width 880 :height 16})

(defn- scene
  ([players] (scene players bounds))
  ([players blast-bounds]
   {:blast-bounds blast-bounds :platforms [floor] :players players}))

(defn- ticks [world n dt]
  (nth (iterate #(game/step % {} dt) world) n))

(defn- close-to? [actual expected]
  #?(:clj (< (Math/abs (- actual expected)) 1e-8)
     :cljs (< (js/Math.abs (- actual expected)) 1e-8)))

(defn- grounded-player [color x]
  (-> (game/make-player color x 84)
      (assoc :spawn-locked? false)))

(deftest protection-starts-clear-and-grants-two-seconds-only-to-active-players
  (let [player (game/make-player "#4ade80" 50 84)
        protected (game/grant-spawn-protection player)
        respawning (assoc player :lifecycle :respawning
                          :respawn-seconds-left 1)
        eliminated (assoc player :lifecycle :eliminated :stocks 0)
        return-ready (assoc player :lifecycle :respawning
                            :stocks 2 :respawn-seconds-left 0.025)
        returned (game/step (scene {:runner return-ready}) {} 0.025)
        fresh (get-in returned [:players :runner])]
    (is (zero? (:protection-seconds-left player)))
    (is (= 2 (:protection-seconds-left protected)))
    (is (zero? (:protection-seconds-left player))
        "granting protection returns a new player value")
    (is (= respawning (game/grant-spawn-protection respawning)))
    (is (= eliminated (game/grant-spawn-protection eliminated)))
    (is (= "#4ade80" (get-in game/initial-world [:players :player-4 :color])))
    (is (= :active (:lifecycle fresh)))
    (is (= 2 (:protection-seconds-left fresh)))
    (is (= [50 84] ((juxt :x :y) fresh))
        "protection is granted on the return tick without moving the new body")))

(deftest targeting-and-resolution-both-exclude-protected-incoming-hits
  (let [attacker-a (-> (grounded-player "#112233" 40)
                       (assoc :facing :right :melee-strike-id 1
                              :melee-resolved-strike-id 0
                              :melee-target-ids #{:shielded}))
        attacker-b (-> (grounded-player "#445566" 120)
                       (assoc :facing :left :melee-strike-id 1
                              :melee-resolved-strike-id 0
                              :melee-target-ids #{:shielded}))
        shielded (game/grant-spawn-protection
                   (grounded-player "#4ade80" 80))
        players {:attacker-a attacker-a :attacker-b attacker-b
                 :shielded shielded}
        resolved (game/apply-melee-hits players)
        exposed-players (assoc resolved :shielded
                               (assoc shielded :protection-seconds-left 0))
        after-replay (game/apply-melee-hits exposed-players)]
    (is (= #{} (game/melee-target-ids players :attacker-a)))
    (is (= #{} (game/melee-target-ids players :attacker-b)))
    (is (= 1 (get-in resolved [:attacker-a :melee-resolved-strike-id])))
    (is (= 1 (get-in resolved [:attacker-b :melee-resolved-strike-id])))
    (is (= shielded (:shielded resolved)))
    (is (= 0 (:accumulated-percentage (:shielded resolved))))
    (is (= [0 0 0 0]
           ((juxt :vx :vy :knockback-vx :knockback-vy) (:shielded resolved))))
    (is (= 0 (:accumulated-percentage (:shielded after-replay)))
        "a rejected strike is consumed and cannot become a delayed hit")))

(deftest protected-players-can-attack-and-protection-expires-before-the-eightieth-tick-query
  (let [protected-attacker (-> (grounded-player "#112233" 40)
                               game/grant-spawn-protection)
        target (grounded-player "#445566" 80)
        outgoing (game/step (scene {:attacker protected-attacker :target target})
                            {:attacker {:attack? true}} 0.025)
        spawn-protected-target (game/grant-spawn-protection target)
        boundary-world (scene {:attacker (grounded-player "#112233" 40)
                               :target spawn-protected-target})
        before-eightieth (ticks boundary-world 79 0.025)
        on-eightieth (game/step before-eightieth
                                {:attacker {:attack? true}} 0.025)]
    (testing "protection blocks incoming hits without blocking outgoing attacks"
      (is (= 1 (get-in outgoing [:players :attacker :melee-strike-id])))
      (is (= 10 (get-in outgoing [:players :target :accumulated-percentage])))
      (is (close-to? 1.975
                     (get-in outgoing [:players :attacker
                                       :protection-seconds-left]))))
    (testing "the 80th later 25 ms tick expires before its target query"
      (is (close-to? 0.025
                     (get-in before-eightieth [:players :target
                                               :protection-seconds-left])))
      (is (zero? (get-in before-eightieth [:players :target
                                           :accumulated-percentage])))
      (is (zero? (get-in on-eightieth [:players :target
                                       :protection-seconds-left])))
      (is (= 10 (get-in on-eightieth [:players :target
                                      :accumulated-percentage])))
      (is (pos? (get-in on-eightieth [:players :target :knockback-vx]))))))

(deftest protection-does-not-prevent-ringouts
  (let [small-bounds {:left 0 :right 100 :top 0 :bottom 100}
        outside (-> (game/make-player "#4ade80" -17 50)
                    (assoc :stocks 2)
                    game/grant-spawn-protection)
        result (game/step (scene {:runner outside} small-bounds) {} 0)
        lost-life (get-in result [:players :runner])]
    (is (= :respawning (:lifecycle lost-life)))
    (is (= 1 (:stocks lost-life)))
    (is (= 3 (:respawn-seconds-left lost-life)))
    (is (zero? (:protection-seconds-left lost-life)))
    (is (false? (game/active-player? lost-life)))))

(deftest protection-expiry-affects-only-the-corresponding-target
  (let [attacker (grounded-player "#22d3ee" 40)
        shielded (-> (grounded-player "#4ade80" 70)
                     game/grant-spawn-protection)
        expiring (assoc (grounded-player "#e11d48" 80)
                        :protection-seconds-left 0.025)
        world (scene {:attacker attacker :shielded shielded :expiring expiring})
        after (game/step world {:attacker {:attack? true}} 0.025)]
    (is (close-to? 1.975 (get-in after [:players :shielded :protection-seconds-left])))
    (is (zero? (get-in after [:players :shielded :accumulated-percentage])))
    (is (zero? (get-in after [:players :shielded :knockback-vx])))
    (is (zero? (get-in after [:players :expiring :protection-seconds-left])))
    (is (= 10 (get-in after [:players :expiring :accumulated-percentage])))
    (is (pos? (get-in after [:players :expiring :knockback-vx])))
    (is (= #{:expiring} (get-in after [:players :attacker :melee-target-ids])))
    (is (= 2 (get-in world [:players :shielded :protection-seconds-left])))))

(ns clojure-arena.melee-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def dt 0.025)
(defn player [] (assoc (game/make-player "#22d3ee" 100 100) :spawn-locked? false))
(defn near? [a b] (< (abs (- a b)) 1e-8))

(deftest a-miss-still-accepts-a-strike-and-sets-its-cooldown
  (let [before (player)
        struck (game/step-player before [] {:attack? true} dt)]
    (is (= 1 (:melee-strike-id struck)))
    (is (= 0.25 (:melee-cooldown-left struck)))
    (is (= 0 (:melee-strike-id before)))))

(deftest cooldown-expires-on-the-tenth-later-tick
  (let [first-strike (game/step-melee (player) true dt)
        early (nth (iterate #(game/step-melee % false dt) first-strike) 9)
        rejected (game/step-melee early true 0)
        second-strike (game/step-melee early true dt)]
    (is (near? 0.025 (:melee-cooldown-left early)))
    (is (= 1 (:melee-strike-id rejected)))
    (is (= 2 (:melee-strike-id second-strike)))
    (is (= 0.25 (:melee-cooldown-left second-strike)))))

(deftest early-clicks-are-discarded-without-replay
  (let [struck (game/step-melee (player) true dt)
        rejected (game/step-melee struck true dt)
        expired (nth (iterate #(game/step-melee % false dt) rejected) 20)]
    (is (= 1 (:melee-strike-id rejected)))
    (is (= 1 (:melee-strike-id expired)))
    (is (zero? (:melee-cooldown-left expired)))
    (is (= 2 (:melee-strike-id (game/step-melee expired true dt))))))

(deftest expiry-tolerance-does-not-accept-a-whole-tick-early
  (is (= 1 (:melee-strike-id
            (game/step-melee (assoc (player) :melee-cooldown-left (+ dt 5e-10)) true dt))))
  (is (= 0 (:melee-strike-id
            (game/step-melee (assoc (player) :melee-cooldown-left 0.05) true dt)))))

(deftest landing-tick-rejects-a-locked-attack
  (let [floor {:id :floor :type :solid :x 0 :y 200 :width 300 :height 8}
        locked (assoc (player) :y 184 :spawn-locked? true)
        landed (game/step-player locked [floor] {:attack? true} dt)
        next-player (game/step-player landed [floor] {} dt)]
    (is (false? (:spawn-locked? landed)))
    (is (= 0 (:melee-strike-id landed)))
    (is (= 0 (:melee-strike-id next-player)))
    (is (= 1 (:melee-strike-id (game/step-player next-player [floor] {:attack? true} dt))))))

(deftest attackers-own-their-cooldowns-and-preserve-other-controls
  (let [world {:platforms [] :players {:a (player) :b (player)}}
        struck (game/step world {:a {:attack? true :horizontal -1 :jump? true}} dt)
        next-world (game/step struck {:a {:attack? true} :b {:attack? true}} dt)]
    (is (= :left (get-in struck [:players :a :facing])))
    (is (== -22 (get-in struck [:players :a :vx])))
    (is (= -395.5 (get-in struck [:players :a :vy])))
    (is (= 0 (get-in struck [:players :a :air-jumps-left])))
    (is (= 1 (get-in next-world [:players :a :melee-strike-id])))
    (is (= 1 (get-in next-world [:players :b :melee-strike-id])))
    (is (near? 0.225 (get-in next-world [:players :a :melee-cooldown-left])))
    (is (= 0.25 (get-in next-world [:players :b :melee-cooldown-left])))))

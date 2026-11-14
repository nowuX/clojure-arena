(ns clojure-arena.melee-resolution-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn player [x]
  (assoc (game/make-player "#22d3ee" x 100) :spawn-locked? false))

(defn strike [targets]
  (assoc (player 100) :melee-strike-id 1 :melee-target-ids targets))

(deftest returned-target-sets-cannot-apply-the-same-hit-again
  (let [before {:a (strike #{:b}) :b (player 140)}
        resolved (game/apply-melee-hits before)
        replayed (game/apply-melee-hits resolved)]
    (is (= resolved replayed))
    (is (= 10 (get-in resolved [:b :accumulated-percentage])))
    (is (= 1 (get-in resolved [:a :melee-resolved-strike-id])))
    (is (= #{:b} (get-in resolved [:a :melee-target-ids])))
    (is (= 0 (get-in before [:a :melee-resolved-strike-id])))))

(deftest a-miss-is-consumed-even-if-old-target-data-is-replayed
  (let [missed (game/apply-melee-hits {:a (strike #{}) :b (player 140)})
        stale (assoc-in missed [:a :melee-target-ids] #{:b})
        result (game/apply-melee-hits stale)]
    (is (= 1 (get-in missed [:a :melee-resolved-strike-id])))
    (is (= 0 (get-in result [:b :accumulated-percentage])))
    (is (= stale result))))

(deftest sequence-numbers-are-local-to-each-attacker
  (let [players {:a (strike #{:c}) :b (strike #{:c}) :c (player 140)}
        result (game/apply-melee-hits players)]
    (is (= 20 (get-in result [:c :accumulated-percentage])))
    (is (= [1 1] (mapv #(get-in result [% :melee-resolved-strike-id]) [:a :b])))
    (is (= result (game/apply-melee-hits result)))))

(deftest a-later-accepted-strike-can-hit-the-same-target-again
  (let [world {:platforms [] :players {:a (player 100) :b (player 140)}}
        first-hit (game/step world {:a {:attack? true}} 0)
        next-hit (game/step first-hit {:a {:attack? true}} 0.25)]
    (is (= 10 (get-in first-hit [:players :b :accumulated-percentage])))
    (is (= 20 (get-in next-hit [:players :b :accumulated-percentage])))
    (is (= 2 (get-in next-hit [:players :a :melee-resolved-strike-id])))
    (is (= (:players next-hit) (game/apply-melee-hits (:players next-hit))))))

(deftest stale-or-missing-targets-do-not-prevent-consuming-a-strike
  (let [result (game/apply-melee-hits {:a (strike #{:missing}) :b (player 140)})]
    (is (= #{:a :b} (set (keys result))))
    (is (= 1 (get-in result [:a :melee-resolved-strike-id])))
    (is (= 0 (get-in result [:b :accumulated-percentage])))))

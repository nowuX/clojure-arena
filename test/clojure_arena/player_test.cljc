(ns clojure-arena.player-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest player-factory-produces-independent-immutable-state
  (let [a (game/make-player "#22d3ee" 400 160)
        b (game/make-player "#e11d48" 260 160)
        world {:players {:a a :b b}}
        changed (update-in world [:players :a] assoc
                           :x 123 :vx 220 :spawn-locked? false
                           :air-jumps-left 0 :drop-through-platform-id :left)]
    (is (every? zero? [(:vx a) (:vy a) (:vx b) (:vy b)]))
    (is (every? :spawn-locked? [a b]))
    (is (= [1 1] (mapv :air-jumps-left [a b])))
    (is (every? nil? (map :drop-through-platform-id [a b])))
    (is (= b (get-in changed [:players :b])))
    (is (= a (get-in world [:players :a])))
    (is (= 400 (:x a)))
    (is (= 260 (:x b)))
    (is (= 16 (:radius a)))))

(deftest fixture-identities-are-distinct-from-their-display-colors
  (let [players (:players game/initial-world)
        recolored (update-vals players #(assoc % :color "#22d3ee"))]
    (is (= #{:player-1 :player-2 :player-3 :player-4} (set (keys players))))
    (is (= 4 (count (set (map :color (vals players))))))
    (is (= #{"#22d3ee" "#e11d48" "#f59e0b" "#4ade80"}
           (set (map :color (vals players)))))
    (is (= (set (keys players)) (set (keys recolored))))
    (is (= [400 160] ((juxt :x :y) (:player-1 players))))
    (is (= [260 160] ((juxt :x :y) (:player-2 players))))
    (is (= [540 160] ((juxt :x :y) (:player-3 players))))
    (is (= [350 160] ((juxt :x :y) (:player-4 players))))))

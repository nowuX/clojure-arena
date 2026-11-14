(ns clojure-arena.scene-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest collection-boundary-preserves-scene-and-free-flight
  (let [world game/initial-world
        platforms (:platforms world)
        airborne (assoc world :platforms [])
        fallen (fixtures/step airborne {} 0.025)]
    (is (vector? platforms))
    (is (= [:main :left :right :upper] (mapv :id platforms)))
    (is (= [:solid :semisolid :semisolid :semisolid] (mapv :type platforms)))
    (is (nil? (game/supporting-platform (fixtures/player-of world) [])))
    (is (> (get-in fallen [:players :player-1 :y]) (get-in airborne [:players :player-1 :y])))
    (is (= platforms (:platforms (fixtures/step world {} 0.025))))))

(deftest visible-semisolids-now-catch-descending-players
  (let [world (update-in game/initial-world [:players :player-1] assoc :x 250 :y 160 :vy 0)
        below (nth (iterate #(fixtures/step % {} 0.025) world) 40)]
    (is (= :left (:id (game/supporting-platform (fixtures/player-of below) (:platforms world)))))
    (is (== 194 (get-in below [:players :player-1 :y])))))

(ns clojure-arena.scene-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest collection-boundary-preserves-scene-and-free-flight
  (let [world game/initial-world
        platforms (:platforms world)
        airborne (assoc world :platforms [])
        fallen (game/step airborne {} 0.025)]
    (is (vector? platforms))
    (is (= [:main :left :right :upper] (mapv :id platforms)))
    (is (= [:solid :semisolid :semisolid :semisolid] (mapv :type platforms)))
    (is (nil? (game/supporting-platform (:player world) [])))
    (is (> (get-in fallen [:player :y]) (get-in airborne [:player :y])))
    (is (= platforms (:platforms (game/step world {} 0.025))))))

(deftest visible-semisolids-now-catch-descending-players
  (let [world (update game/initial-world :player assoc :x 250 :y 160 :vy 0)
        below (nth (iterate #(game/step % {} 0.025) world) 40)]
    (is (= :left (:id (game/supporting-platform (:player below) (:platforms world)))))
    (is (== 194 (get-in below [:player :y])))))

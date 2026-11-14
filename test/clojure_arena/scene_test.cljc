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
    (is (= [:main] (mapv :id platforms)))
    (is (= [:solid] (mapv :type platforms)))
    (is (nil? (game/supporting-platform (:player world) [])))
    (is (> (get-in fallen [:player :y]) (get-in airborne [:player :y])))
    (is (= platforms (:platforms (game/step world {} 0.025))))))

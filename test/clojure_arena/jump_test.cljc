(ns clojure-arena.jump-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def dt 0.025)
(def platform (:platform game/initial-world))
(def ground-world
  (update game/initial-world :player assoc :y 264 :vy 0 :spawn-locked? false))
(defn near? [a b] (< (abs (- a b)) 1e-8))

(deftest ground-launch-precedes-gravity-and-preserves-horizontal-rules
  (let [result (game/step ground-world {:horizontal 1 :jump? true} dt)]
    (is (near? -395.5 (get-in result [:player :vy])))
    (is (near? 254.1125 (get-in result [:player :y])))
    (is (near? 400.55 (get-in result [:player :x])))
    (is (not (game/grounded? (:player result) platform)))
    (is (= 264 (get-in ground-world [:player :y])))))

(deftest airborne-requests-are-discarded-instead-of-buffered
  (let [airborne (game/step ground-world {:jump? true} dt)
        repeated (game/step airborne {:jump? true} dt)
        falling (update ground-world :player assoc :y 263 :vy 100)
        landed (game/step falling {:jump? true} dt)
        standing (game/step landed {} dt)
        fresh (game/step standing {:jump? true} dt)]
    (is (near? -371 (get-in repeated [:player :vy])))
    (is (game/grounded? (:player landed) platform))
    (is (game/grounded? (:player standing) platform))
    (is (near? -395.5 (get-in fresh [:player :vy])))))

(deftest ascending-motion-stops-at-the-underside
  (let [world (update ground-world :player assoc :y 330 :vy -1000)
        result (game/step world {} dt)]
    (is (near? 312 (get-in result [:player :y])))
    (is (zero? (get-in result [:player :vy])))
    (is (not (game/grounded? (:player result) platform)))))

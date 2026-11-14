(ns clojure-arena.collision-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def platform (:platform game/initial-world))
(defn near? [a b] (< (abs (- a b)) 1e-8))
(defn player-at [x y vx vy]
  (assoc (:player game/initial-world) :x x :y y :vx vx :vy vy))

(deftest descending-crossing-and-stable-standing
  (let [world (assoc game/initial-world :player (player-at 400 50 0 20000))
        landed (game/step world {} 0.025)
        standing (nth (iterate #(game/step % {} 0.025) landed) 80)]
    (is (near? 264 (get-in landed [:player :y])))
    (is (zero? (get-in landed [:player :vy])))
    (is (= (:player landed) (:player standing)))
    (is (= 50 (get-in world [:player :y])))))

(deftest top-support-ends-at-both-center-endpoints
  (doseq [[x horizontal] [[170 -1] [630 1]]]
    (let [world (assoc game/initial-world :player (player-at x 264 0 0))
          next-world (game/step world {:horizontal horizontal} 0.025)]
      (is (> (get-in next-world [:player :y]) 264))
      (is (pos? (get-in next-world [:player :vy])))))
  (doseq [x [150 650]]
    (let [world (assoc game/initial-world :player (player-at x 263 0 100))]
      (is (> (get-in (game/step world {} 0.025) [:player :y]) 264)))))

(deftest landing-uses-x-at-crossing-time
  (let [player (player-at 150 250 2000 1000)
        result (game/land-on-top player platform 0.025)]
    (is (near? 264 (:y result)))
    (is (near? 200 (:x result)))))

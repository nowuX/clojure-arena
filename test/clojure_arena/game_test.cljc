(ns clojure-arena.game-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)
(defn near? [a b] (< (abs (- a b)) 1e-8))
(defn steps [world inputs n]
  (nth (iterate #(game/step % inputs dt) world) n))

(deftest constant-speed-and-immutable-inputs
  (let [world game/initial-world
        inputs {:horizontal 1}
        right (game/step world inputs dt)
        left (game/step world {:horizontal -1} dt)
        neutral (game/step right {:horizontal 0} dt)]
    (is (near? 405.5 (get-in right [:player :x])))
    (is (near? 394.5 (get-in left [:player :x])))
    (is (= 220 (get-in right [:player :vx])))
    (is (= 0 (get-in neutral [:player :vx])))
    (is (= (get-in right [:player :x]) (get-in neutral [:player :x])))
    (is (near? 620 (get-in (steps world inputs 40) [:player :x])))
    (is (= 400 (get-in world [:player :x])))
    (is (= {:horizontal 1} inputs))
    (is (= (:platform world) (:platform right)))))

(deftest gravity-precedes-position-and-does-not-mutate-world
  (let [world game/initial-world
        first-tick (game/step world {:horizontal 1} dt)
        second-tick (game/step first-tick {} dt)]
    (is (near? 24.5 (get-in first-tick [:player :vy])))
    (is (near? 160.6125 (get-in first-tick [:player :y])))
    (is (near? 49 (get-in second-tick [:player :vy])))
    (is (near? 161.8375 (get-in second-tick [:player :y])))
    (is (= 160 (get-in world [:player :y])))
    (is (near? 264 (get-in (steps world {} 40) [:player :y])))))

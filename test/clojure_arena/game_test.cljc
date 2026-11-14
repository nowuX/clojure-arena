(ns clojure-arena.game-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def playable-world
  (assoc-in game/initial-world [:player :spawn-locked?] false))

(def dt 0.025)
(defn near? [a b] (< (abs (- a b)) 1e-8))
(defn steps [world inputs n]
  (nth (iterate #(game/step % inputs dt) world) n))

(deftest accelerated-movement-and-immutable-inputs
  (let [world playable-world
        inputs {:horizontal 1}
        right (game/step world inputs dt)
        left (game/step world {:horizontal -1} dt)
        neutral (game/step right {:horizontal 0} dt)]
    (is (near? 400.55 (get-in right [:player :x])))
    (is (near? 399.45 (get-in left [:player :x])))
    (is (== 22 (get-in right [:player :vx])))
    (is (= 0 (get-in neutral [:player :vx])))
    (is (= (get-in right [:player :x]) (get-in neutral [:player :x])))
    (is (near? 595.25 (get-in (steps world inputs 40) [:player :x])))
    (is (= 400 (get-in world [:player :x])))
    (is (= {:horizontal 1} inputs))
    (is (= (:platform world) (:platform right)))))

(deftest gravity-precedes-position-and-does-not-mutate-world
  (let [world playable-world
        first-tick (game/step world {:horizontal 1} dt)
        second-tick (game/step first-tick {} dt)]
    (is (near? 24.5 (get-in first-tick [:player :vy])))
    (is (near? 160.6125 (get-in first-tick [:player :y])))
    (is (near? 49 (get-in second-tick [:player :vy])))
    (is (near? 161.8375 (get-in second-tick [:player :y])))
    (is (= 160 (get-in world [:player :y])))
    (is (near? 264 (get-in (steps world {} 40) [:player :y])))))

(deftest acceleration-caps-and-reverses-without-overshoot
  (let [speeds (take 11 (iterate #(game/accelerate-horizontal % 1 dt) 0))]
    (is (every? true? (map == (range 0 221 22) speeds)))
    (is (= 220 (game/accelerate-horizontal 215 1 dt)))
    (is (== 198 (game/accelerate-horizontal 220 -1 dt)))
    (is (== -220 (nth (iterate #(game/accelerate-horizontal % -1 dt) 220) 20)))
    (is (= -220 (game/accelerate-horizontal -215 -1 dt)))
    (is (= 192.5 (game/accelerate-horizontal 220 0 dt)))))

(deftest neutral-friction-stops-in-eight-ticks-without-sign-reversal
  (doseq [sign [1 -1]]
    (let [world (assoc-in playable-world [:player :vx] (* sign 220))
          states (take 9 (iterate #(game/step % {:horizontal 0} dt) world))
          stopped (last states)]
      (is (near? (* sign 192.5) (get-in (second states) [:player :vx])))
      (is (near? (+ 400 (* sign 19.25)) (get-in stopped [:player :x])))
      (is (zero? (get-in stopped [:player :vx])))
      (is (every? #(<= 0 (* sign (get-in % [:player :vx]))) states))
      (is (= 0 (game/apply-horizontal-friction (* sign 10) dt)))))
  (doseq [y [160 264]]
    (let [world (update playable-world :player assoc :y y :vx 220)]
      (is (= 192.5 (get-in (game/step world {} dt) [:player :vx]))))))

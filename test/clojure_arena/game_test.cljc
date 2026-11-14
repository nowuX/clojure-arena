(ns clojure-arena.game-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def playable-world
  (assoc-in fixtures/single-platform-world [:players :player-1 :spawn-locked?] false))

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
    (is (near? 400.55 (get-in right [:players :player-1 :x])))
    (is (near? 399.45 (get-in left [:players :player-1 :x])))
    (is (== 22 (get-in right [:players :player-1 :vx])))
    (is (= 0 (get-in neutral [:players :player-1 :vx])))
    (is (= (get-in right [:players :player-1 :x]) (get-in neutral [:players :player-1 :x])))
    (is (near? 595.25 (get-in (steps world inputs 40) [:players :player-1 :x])))
    (is (= 400 (get-in world [:players :player-1 :x])))
    (is (= {:horizontal 1} inputs))
    (is (= (:platforms world) (:platforms right)))))

(deftest gravity-precedes-position-and-does-not-mutate-world
  (let [world playable-world
        first-tick (game/step world {:horizontal 1} dt)
        second-tick (game/step first-tick {} dt)]
    (is (near? 24.5 (get-in first-tick [:players :player-1 :vy])))
    (is (near? 160.6125 (get-in first-tick [:players :player-1 :y])))
    (is (near? 49 (get-in second-tick [:players :player-1 :vy])))
    (is (near? 161.8375 (get-in second-tick [:players :player-1 :y])))
    (is (= 160 (get-in world [:players :player-1 :y])))
    (is (near? 264 (get-in (steps world {} 40) [:players :player-1 :y])))))

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
    (let [world (assoc-in playable-world [:players :player-1 :vx] (* sign 220))
          states (take 9 (iterate #(game/step % {:horizontal 0} dt) world))
          stopped (last states)]
      (is (near? (* sign 192.5) (get-in (second states) [:players :player-1 :vx])))
      (is (near? (+ 400 (* sign 19.25)) (get-in stopped [:players :player-1 :x])))
      (is (zero? (get-in stopped [:players :player-1 :vx])))
      (is (every? #(<= 0 (* sign (get-in % [:players :player-1 :vx]))) states))
      (is (= 0 (game/apply-horizontal-friction (* sign 10) dt)))))
  (doseq [y [160 264]]
    (let [world (update-in playable-world [:players :player-1] assoc :y y :vx 220)]
      (is (= 192.5 (get-in (game/step world {} dt) [:players :player-1 :vx]))))))

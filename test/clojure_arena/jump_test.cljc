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

(deftest exhausted-airborne-requests-are-discarded-instead-of-buffered
  (let [airborne (assoc-in (game/step ground-world {:jump? true} dt) [:player :air-jumps-left] 0)
        repeated (game/step airborne {:jump? true} dt)
        falling (update ground-world :player assoc :y 263 :vy 100 :air-jumps-left 0)
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

(deftest air-launch-spends-one-allowance-and-ground-launch-preserves-it
  (let [first-jump (game/step ground-world {:jump? true} dt)
        airborne-request (game/step first-jump {:jump? true} dt)]
    (is (= 1 (get-in game/initial-world [:player :air-jumps-left])))
    (is (= 1 (get-in first-jump [:player :air-jumps-left])))
    (is (= 0 (get-in airborne-request [:player :air-jumps-left])))
    (is (near? -395.5 (get-in airborne-request [:player :vy])))))

(deftest only-supported-top-landing-restores-the-allowance
  (let [falling (update ground-world :player assoc :y 250 :vy 1000 :air-jumps-left 0)
        landed (game/step falling {} dt)
        departed (game/step (assoc-in ground-world [:player :x] 630)
                            {:horizontal 1} dt)]
    (is (= 1 (get-in landed [:player :air-jumps-left])))
    (is (= 1 (get-in departed [:player :air-jumps-left]))))
  (doseq [[x y vx vy] [[150 288 1000 0] [400 330 0 -1000] [150 260 1000 400]]]
    (let [player (assoc (:player ground-world) :x x :y y :vx vx :vy vy :air-jumps-left 0)
          result (game/collide-platform player platform dt)]
      (is (zero? (:air-jumps-left result))))))

(deftest air-launch-replaces-ascent-apex-and-descent-velocity
  (doseq [vy [-200 0 200]]
    (let [airborne (update ground-world :player assoc :y 160 :vy vy)
          result (game/step airborne {:horizontal -1 :jump? true} dt)]
      (is (near? -395.5 (get-in result [:player :vy])))
      (is (near? 150.1125 (get-in result [:player :y])))
      (is (zero? (get-in result [:player :air-jumps-left])))
      (is (near? 399.45 (get-in result [:player :x]))))))

(deftest third-request-is-rejected-and-landing-restores-a-new-cycle
  (let [ground-jump (game/step ground-world {:jump? true} dt)
        air-jump (game/step ground-jump {:jump? true} dt)
        third (game/step air-jump {:jump? true} dt)
        landed (first (filter #(game/grounded? (:player %) platform)
                               (take 100 (iterate #(game/step % {} dt) third))))]
    (is (near? -371 (get-in third [:player :vy])))
    (is (zero? (get-in third [:player :air-jumps-left])))
    (is (some? landed))
    (when landed
      (is (= 1 (get-in landed [:player :air-jumps-left])))
      (is (near? -395.5 (get-in (game/step landed {:jump? true} dt) [:player :vy]))))))

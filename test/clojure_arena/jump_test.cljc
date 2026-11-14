(ns clojure-arena.jump-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def dt 0.025)
(def platform (first (:platforms fixtures/single-platform-world)))
(def ground-world
  (update-in fixtures/single-platform-world [:players :player-1] assoc :y 264 :vy 0 :spawn-locked? false))
(defn near? [a b] (< (abs (- a b)) 1e-8))

(deftest ground-launch-precedes-gravity-and-preserves-horizontal-rules
  (let [result (game/step ground-world {:horizontal 1 :jump? true} dt)]
    (is (near? -395.5 (get-in result [:players :player-1 :vy])))
    (is (near? 254.1125 (get-in result [:players :player-1 :y])))
    (is (near? 400.55 (get-in result [:players :player-1 :x])))
    (is (not (game/grounded? (fixtures/player-of result) platform)))
    (is (= 264 (get-in ground-world [:players :player-1 :y])))))

(deftest exhausted-airborne-requests-are-discarded-instead-of-buffered
  (let [airborne (assoc-in (game/step ground-world {:jump? true} dt) [:players :player-1 :air-jumps-left] 0)
        repeated (game/step airborne {:jump? true} dt)
        falling (update-in ground-world [:players :player-1] assoc :y 263 :vy 100 :air-jumps-left 0)
        landed (game/step falling {:jump? true} dt)
        standing (game/step landed {} dt)
        fresh (game/step standing {:jump? true} dt)]
    (is (near? -371 (get-in repeated [:players :player-1 :vy])))
    (is (game/grounded? (fixtures/player-of landed) platform))
    (is (game/grounded? (fixtures/player-of standing) platform))
    (is (near? -395.5 (get-in fresh [:players :player-1 :vy])))))

(deftest ascending-motion-stops-at-the-underside
  (let [world (update-in ground-world [:players :player-1] assoc :y 330 :vy -1000)
        result (game/step world {} dt)]
    (is (near? 312 (get-in result [:players :player-1 :y])))
    (is (zero? (get-in result [:players :player-1 :vy])))
    (is (not (game/grounded? (fixtures/player-of result) platform)))))

(deftest air-launch-spends-one-allowance-and-ground-launch-preserves-it
  (let [first-jump (game/step ground-world {:jump? true} dt)
        airborne-request (game/step first-jump {:jump? true} dt)]
    (is (= 1 (get-in fixtures/single-platform-world [:players :player-1 :air-jumps-left])))
    (is (= 1 (get-in first-jump [:players :player-1 :air-jumps-left])))
    (is (= 0 (get-in airborne-request [:players :player-1 :air-jumps-left])))
    (is (near? -395.5 (get-in airborne-request [:players :player-1 :vy])))))

(deftest only-supported-top-landing-restores-the-allowance
  (let [falling (update-in ground-world [:players :player-1] assoc :y 250 :vy 1000 :air-jumps-left 0)
        landed (game/step falling {} dt)
        departed (game/step (assoc-in ground-world [:players :player-1 :x] 630)
                            {:horizontal 1} dt)]
    (is (= 1 (get-in landed [:players :player-1 :air-jumps-left])))
    (is (= 1 (get-in departed [:players :player-1 :air-jumps-left]))))
  (doseq [[x y vx vy] [[150 288 1000 0] [400 330 0 -1000] [150 260 1000 400]]]
    (let [player (assoc (fixtures/player-of ground-world) :x x :y y :vx vx :vy vy :air-jumps-left 0)
          result (game/collide-platforms player [platform] dt)]
      (is (zero? (:air-jumps-left result))))))

(deftest air-launch-replaces-ascent-apex-and-descent-velocity
  (doseq [vy [-200 0 200]]
    (let [airborne (update-in ground-world [:players :player-1] assoc :y 160 :vy vy)
          result (game/step airborne {:horizontal -1 :jump? true} dt)]
      (is (near? -395.5 (get-in result [:players :player-1 :vy])))
      (is (near? 150.1125 (get-in result [:players :player-1 :y])))
      (is (zero? (get-in result [:players :player-1 :air-jumps-left])))
      (is (near? 399.45 (get-in result [:players :player-1 :x]))))))

(deftest third-request-is-rejected-and-landing-restores-a-new-cycle
  (let [ground-jump (game/step ground-world {:jump? true} dt)
        air-jump (game/step ground-jump {:jump? true} dt)
        third (game/step air-jump {:jump? true} dt)
        landed (first (filter #(game/grounded? (fixtures/player-of %) platform)
                               (take 100 (iterate #(game/step % {} dt) third))))]
    (is (near? -371 (get-in third [:players :player-1 :vy])))
    (is (zero? (get-in third [:players :player-1 :air-jumps-left])))
    (is (some? landed))
    (when landed
      (is (= 1 (get-in landed [:players :player-1 :air-jumps-left])))
      (is (near? -395.5 (get-in (game/step landed {:jump? true} dt) [:players :player-1 :vy]))))))

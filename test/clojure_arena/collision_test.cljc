(ns clojure-arena.collision-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def platform (:platform game/initial-world))
(defn near? [a b] (< (abs (- a b)) 1e-8))
(defn player-at [x y vx vy]
  (assoc (:player game/initial-world) :x x :y y :vx vx :vy vy :spawn-locked? false))

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
        result (game/collide-platform player platform 0.025)]
    (is (near? 264 (:y result)))
    (is (near? 200 (:x result)))))

(deftest swept-faces-prevent-tunneling
  (doseq [[player key expected] [[(player-at 0 288 20000 0) :x 154]
                                [(player-at 800 288 -20000 0) :x 646]
                                [(player-at 400 450 0 -20000) :y 312]
                                [(player-at 400 0 0 20000) :y 264]]]
    (let [result (game/collide-platform player platform 0.025)]
      (is (near? expected (key result)))
      (is (zero? (if (= key :x) (:vx result) (:vy result)))))))

(deftest all-rounded-corners-and-tangential-motion
  (doseq [[cx cy sx sy] [[170 280 -1 -1] [630 280 1 -1]
                         [170 296 -1 1] [630 296 1 1]]]
    (let [player (player-at (+ cx (* sx 40)) (+ cy (* sy 40))
                            (* sx -2000) (* sy -2000))
          result (game/collide-platform player platform 0.025)
          distance (game/sqrt (+ (* (- (:x result) cx) (- (:x result) cx))
                                 (* (- (:y result) cy) (- (:y result) cy))))]
      (is (near? 16 distance))))
  (let [player (player-at 150 260 1000 400)
        contact (game/platform-contact player (game/move-player player 0.025) platform)
        result (game/resolve-contact player contact)
        {:keys [nx ny]} contact]
    (is (some? contact))
    (is (near? 0 (+ (* (:vx result) nx) (* (:vy result) ny))))
    (is (near? (- (* (:vy player) nx) (* (:vx player) ny))
               (- (* (:vy result) nx) (* (:vx result) ny))))))

(deftest true-circle-corner-clearance-and-overlap-recovery
  (let [miss (player-at 154.1 264.1 -10 -10)]
    (is (nil? (game/platform-contact miss (game/move-player miss 0.025) platform))))
  (let [player (player-at 160 288 -100 0)
        result (game/collide-platform player platform 0.025)]
    (is (near? 151.5 (:x result)))
    (is (== -100 (:vx result)))
    (is (= 160 (:x player))))
  (let [player (player-at 400 285 0 0)
        result (game/collide-platform player platform 0.025)]
    (is (near? 264 (:y result)))))

(deftest repeated-zero-time-contacts-have-a-finite-budget
  (let [calls (atom 0)
        player (player-at 400 100 1 1)]
    (with-redefs [game/platform-contact
                  (fn [p _ _]
                    (swap! calls inc)
                    {:time 0 :x (:x p) :y (:y p) :nx 0 :ny -1})]
      (let [result (game/collide-platform player platform 0.025)]
        (is (= 4 @calls))
        (is (= 400 (:x result)))
        (is (zero? (:vy result)))))))

(ns clojure-arena.spawn-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def dt 0.025)
(def platform (first (:platforms fixtures/single-platform-world)))
(defn near? [a b] (< (abs (- a b)) 1e-8))

(deftest locked-fall-unlocks-on-top-and-moves-on-the-following-tick
  (let [inputs {:horizontal 1 :jump? true}
        falling (take-while #(get-in % [:player :spawn-locked?])
                           (iterate #(game/step % inputs dt) fixtures/single-platform-world))
        landed (game/step (last falling) inputs dt)
        next-world (game/step landed {:horizontal 1} dt)]
    (is (seq falling))
    (is (every? #(== 400 (get-in % [:player :x])) falling))
    (is (every? #(zero? (get-in % [:player :vx])) falling))
    (is (false? (get-in landed [:player :spawn-locked?])))
    (is (near? 400 (get-in landed [:player :x])))
    (is (game/grounded? (:player landed) platform))
    (is (near? 400.55 (get-in next-world [:player :x])))
    (is (= inputs {:horizontal 1 :jump? true}))))

(deftest support-is-only-the-flat-top-with-zero-vertical-speed
  (let [player (assoc (:player fixtures/single-platform-world) :y 264 :vy 0)]
    (doseq [x [170 400 630]]
      (is (game/grounded? (assoc player :x x) platform)))
    (doseq [changes [{:x 169.999} {:x 630.001} {:vy 1} {:y 264.001} {:y 312}]]
      (is (not (game/grounded? (merge player changes) platform))))
    (is (= platform (game/supporting-platform player [platform])))
    (is (nil? (game/supporting-platform (assoc player :vy -1) [platform])))))

(deftest non-top-contacts-do-not-unlock-and-edge-departure-does-not-relock
  (doseq [[x y vx vy] [[150 288 1000 0] [400 330 0 -1000] [150 260 1000 400]]]
    (let [player (assoc (:player fixtures/single-platform-world) :x x :y y :vx vx :vy vy)
          result (game/collide-platforms player [platform] dt)]
      (is (:spawn-locked? result))))
  (let [world (update fixtures/single-platform-world :player assoc
                      :spawn-locked? false :x 630 :y 264 :vx 220 :vy 0)
        result (game/step world {:horizontal 1} dt)]
    (is (> (get-in result [:player :y]) 264))
    (is (false? (get-in result [:player :spawn-locked?])))))

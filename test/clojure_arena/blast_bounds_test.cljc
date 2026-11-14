(ns clojure-arena.blast-bounds-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest whole-circle-exits-are-strict-on-every-edge
  (let [bounds (:blast-bounds game/initial-world)
        player (game/make-player "#22d3ee" 400 160)]
    (doseq [[x y] [[-96 160] [896 160] [400 -176] [400 576]]]
      (is (false? (game/outside-arena? (assoc player :x x :y y) bounds))))
    (doseq [[x y] [[-96.01 160] [896.01 160] [400 -176.01] [400 576.01]]]
      (is (game/outside-arena? (assoc player :x x :y y) bounds)))
    (doseq [[x y] [[-81 160] [881 160] [400 -161] [400 561] [820 160]]]
      (is (false? (game/outside-arena? (assoc player :x x :y y) bounds))))
    (is (false? (game/outside-arena? player nil)))
    (is (game/outside-arena? (game/move-player (assoc player :vx 10000) 0.1) bounds))
    (is (= player (game/make-player "#22d3ee" 400 160)))))

(deftest snapshot-bounds-require-exact-finite-noninverted-data
  (let [snapshot {:type :snapshot :tick 0 :world (match-helpers/running-world game/initial-world)}
        bounds (:blast-bounds game/initial-world)]
    (is (protocol/snapshot-message? snapshot))
    (is (= snapshot (protocol/decode (pr-str snapshot))))
    (doseq [invalid [nil {} (dissoc bounds :left) (assoc bounds :extra 1)
                     (assoc bounds :left 880) (assoc bounds :top 600)
                     (assoc bounds :right ##Inf) (assoc bounds :top ##NaN)
                     (assoc bounds :bottom "560")]]
      (is (not (protocol/snapshot-message? (assoc-in snapshot [:world :blast-bounds] invalid)))))
    (is (not (protocol/snapshot-message? (update snapshot :world dissoc :blast-bounds))))))

(ns clojure-arena.knockback-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(defn- close-to? [actual expected]
  #?(:clj (<= (Math/abs (- actual expected)) 1e-8)
     :cljs (<= (js/Math.abs (- actual expected)) 1e-8)))

(defn- close-impulse? [actual expected]
  (and (close-to? (:dvx actual) (:dvx expected))
       (close-to? (:dvy actual) (:dvy expected))))

(deftest one-right-hit-at-ten-percent-uses-the-specified-components
  (let [right (game/melee-knockback-impulse [:right] 10)
        left (game/melee-knockback-impulse [:left] 10)]
    (is (close-impulse? right {:dvx 184 :dvy -138}))
    (is (close-impulse? left {:dvx -184 :dvy -138}))))

(deftest opposing-hits-add-vertical-components-and-cancel-horizontal
  (is (close-impulse? (game/melee-knockback-impulse [:right :left] 20)
                      {:dvx 0 :dvy -312})))

(deftest empty-hit-groups-add-nothing-and-scaling-continues-above-one-hundred
  (is (close-impulse? (game/melee-knockback-impulse [] 250)
                      {:dvx 0 :dvy 0}))
  (is (close-impulse? (game/melee-knockback-impulse [:right] 150)
                      {:dvx 520 :dvy -390})))

(deftest explicit-configuration-controls-base-and-percentage-scale
  (is (close-impulse? (game/melee-knockback-impulse
                        [:right] 12.5
                        {:base-impulse 50 :percentage-scale 4})
                      {:dvx 80 :dvy -60})))

(deftest facing-order-does-not-change-the-combined-impulse
  (let [orders [[:left :left :right :right]
                [:left :right :left :right]
                [:left :right :right :left]
                [:right :left :left :right]
                [:right :left :right :left]
                [:right :right :left :left]]
        results (mapv #(game/melee-knockback-impulse % 33.5) orders)
        expected {:dvx 0 :dvy -721.2}]
    (is (every? #(close-impulse? % expected) results))
    (is (every? #(and (close-to? (:dvx %) (:dvx (first results)))
                      (close-to? (:dvy %) (:dvy (first results))))
                (rest results)))))

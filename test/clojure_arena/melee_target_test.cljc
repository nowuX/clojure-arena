(ns clojure-arena.melee-target-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn player [x y facing]
  (assoc (game/make-player "#22d3ee" x y) :spawn-locked? false :facing facing))

(deftest forward-half-disk-includes-the-curve-but-not-the-center-line
  (doseq [[facing sign] [[:right 1] [:left -1]]]
    (let [players {:a (player 100 100 facing)
                   :near (player (+ 100 (* sign 30)) 100 :right)
                   :edge (player (+ 100 (* sign 60)) 100 :right)
                   :arc (player (+ 100 (* sign 36)) 148 :right)
                   :far (player (+ 100 (* sign 60.001)) 100 :right)
                   :vertical (player 100 130 :right)
                   :same-center (player 100 100 :right)
                   :behind (player (- 100 (* sign 10)) 100 :right)}]
      (is (= #{:near :edge :arc} (game/melee-target-ids players :a)))
      (is (= #{} (game/melee-target-ids players :missing))))))

(deftest targeting-uses-final-movement-for-every-player
  (let [world {:platforms []
               :players {:a (assoc (player 100 100 :right) :vx 220)
                         :b (player 164 100 :left)}}
        hit (game/step world {:a {:horizontal 1 :attack? true}} 0.025)
        leaving (game/step (assoc-in world [:players :a :vx] 0)
                           {:a {:attack? true} :b {:horizontal 1}} 0.1)]
    (is (= #{} (game/melee-target-ids (:players world) :a)))
    (is (= #{:b} (get-in hit [:players :a :melee-target-ids])))
    (is (= #{} (get-in leaving [:players :a :melee-target-ids])))))

(deftest platforms-do-not-occlude-the-geometric-query
  (let [world {:platforms [{:id :wall :type :solid :x 120 :y 0 :width 10 :height 300}]
               :players {:a (player 100 100 :right) :b (player 150 100 :left)}}
        struck (game/step world {:a {:attack? true}} 0)]
    (is (= #{:b} (get-in struck [:players :a :melee-target-ids])))))

(deftest a-miss-is-not-queried-again-when-a-target-enters-later
  (let [world {:platforms [] :players {:a (player 100 100 :right) :b (player 161 100 :left)}}
        missed (game/step world {:a {:attack? true}} 0)
        entered (game/step missed {:b {:horizontal -1}} 0.1)]
    (is (= 1 (get-in missed [:players :a :melee-strike-id])))
    (is (= #{} (get-in missed [:players :a :melee-target-ids])))
    (is (= #{:b} (game/melee-target-ids (:players entered) :a)))
    (is (= #{} (get-in entered [:players :a :melee-target-ids])))))

(deftest simultaneous-attackers-read-one-snapshot-independent-of-map-order
  (let [entries [[:a (player 100 100 :right)]
                 [:b (player 150 100 :left)]
                 [:c (player 130 100 :left)]]
        inputs {:a {:attack? true} :b {:attack? true}}
        forward (game/step {:platforms [] :players (into (array-map) entries)} inputs 0)
        reversed (game/step {:platforms [] :players (into (array-map) (reverse entries))} inputs 0)]
    (is (= forward reversed))
    (is (= #{:b :c} (get-in forward [:players :a :melee-target-ids])))
    (is (= #{:a :c} (get-in forward [:players :b :melee-target-ids])))
    (is (= #{} (get-in forward [:players :c :melee-target-ids])))))

(deftest contacts-finish-before-target-selection
  (let [floor {:id :floor :type :solid :x 0 :y 200 :width 300 :height 8}
        world {:platforms [floor]
               :players {:a (player 100 184 :right)
                         :b (assoc (player 150 100 :left) :vy 4000)}}
        hit (game/step world {:a {:attack? true}} 0.025)]
    (is (== 184 (get-in hit [:players :b :y])))
    (is (= #{:b} (get-in hit [:players :a :melee-target-ids])))))

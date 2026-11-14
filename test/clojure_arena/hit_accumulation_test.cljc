(ns clojure-arena.hit-accumulation-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn player [x facing percentage]
  (assoc (game/make-player "#22d3ee" x 100)
         :spawn-locked? false :facing facing :accumulated-percentage percentage))

(deftest each-selected-hit-adds-ten-without-a-cap
  (doseq [[before after] [[0 10] [40 50] [100 110] [190 200]]]
    (let [world {:platforms [] :players {:a (player 100 :right 0) :b (player 140 :left before)}}
          struck (game/step world {:a {:attack? true}} 0)]
      (is (= after (get-in struck [:players :b :accumulated-percentage])))
      (is (= before (get-in world [:players :b :accumulated-percentage])))
      (is (zero? (get-in struck [:players :a :accumulated-percentage]))))))

(deftest simultaneous-hits-add-independently-to-each-target
  (let [world {:platforms [] :players {:a (player 100 :right 30)
                                     :b (player 180 :left 20)
                                     :c (player 140 :right 40)
                                     :unrelated (player 500 :left 90)}}
        result (game/step world {:a {:attack? true} :b {:attack? true}} 0)]
    (is (= 60 (get-in result [:players :c :accumulated-percentage])))
    (is (= [30 20 90] (mapv #(get-in result [:players % :accumulated-percentage])
                            [:a :b :unrelated])))))

(deftest misses-and-normal-ticks-do-not-change-percentage
  (let [world {:platforms [] :players {:a (player 100 :left 40) :b (player 140 :left 70)}}
        result (game/step world {:a {:attack? true}} 0)]
    (is (= [40 70] (mapv :accumulated-percentage (vals (:players result)))))
    (is (= (:players result) (:players (game/step result {} 0))))))

(deftest percentage-is-independent-of-voluntary-speed-and-physical-eligibility
  (let [low (assoc (player 100 :right 0) :vx 100 :vy -100)
        high (assoc low :accumulated-percentage 500)
        input {:horizontal 1 :jump? true}
        a (game/step-player low [] input 0.025)
        b (game/step-player high [] input 0.025)]
    (is (= (dissoc a :accumulated-percentage) (dissoc b :accumulated-percentage)))
    (is (= 500 (:accumulated-percentage b)))))

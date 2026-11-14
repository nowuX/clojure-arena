(ns clojure-arena.facing-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)

(defn- player [x y]
  {:x x
   :y y
   :vx 0
   :vy 0
   :radius 16
   :spawn-locked? false
   :air-jumps-left 1
   :drop-through-platform-id nil
   :color "#ffffff"
   :facing :right})

(defn- near? [actual expected]
  #?(:clj (< (Math/abs (- actual expected)) 1e-8)
     :cljs (< (js/Math.abs (- actual expected)) 1e-8)))

(deftest new-players-face-right-by-default
  (is (= :right (:facing (game/make-player "#ffffff" 40 20))))
  (is (every? #(= :right (:facing %))
              (vals (:players game/initial-world)))))

(deftest facing-follows-reversing-intention-before-velocity-reverses
  (let [before (assoc (player 40 20) :vx 100)
        after (game/step-player before [] {:horizontal -1} dt)]
    (is (= :left (:facing after)))
    (is (near? (:vx after) 78))
    (is (pos? (:vx after))
        "the requested direction changes before existing rightward momentum")))

(deftest locked-spawn-ignores-direction-and-keeps-its-facing
  (let [before (assoc (player 40 20)
                      :facing :left
                      :spawn-locked? true)
        after (game/step-player before [] {:horizontal 1} dt)]
    (is (= :left (:facing after)))
    (is (near? (:x after) 40))
    (is (zero? (:vx after)))
    (is (> (:y after) 20))
    (is (pos? (:vy after)))))

(deftest neutral-both-key-blur-and-velocity-only-steps-preserve-facing
  (let [world {:platforms []
               :players {:neutral (assoc (player 40 20) :facing :left)
                         :both-keys (assoc (player 80 40) :facing :right)
                         :braking (assoc (player 120 60)
                                         :facing :left :vx 220)
                         :blurred (assoc (player 160 80)
                                         :facing :right :vx -220)
                         :external-velocity (assoc (player 200 100)
                                                   :facing :right :vx -100)}}
        next-world (game/step world
                              {:neutral {:horizontal 0}
                               :both-keys {:horizontal 0}
                               :braking nil}
                              dt)
        players (:players next-world)]
    (is (= :left (get-in players [:neutral :facing])))
    (is (= :right (get-in players [:both-keys :facing])))
    (is (= :left (get-in players [:braking :facing])))
    (is (= :right (get-in players [:blurred :facing])))
    (is (= :right (get-in players [:external-velocity :facing])))
    (is (near? (get-in players [:braking :vx]) 192.5))
    (is (pos? (get-in players [:braking :vx])))
    (is (near? (get-in players [:blurred :vx]) -192.5))
    (is (near? (get-in players [:external-velocity :vx]) -72.5))))

(deftest each-player-remembers-only-their-own-accepted-intention
  (let [world {:platforms []
               :players {:turning-left (player 40 20)
                         :turning-right (assoc (player 80 20) :facing :left)
                         :coasting (assoc (player 120 20)
                                          :facing :left :vx 75)}}
        next-world (game/step world
                              {:turning-left {:horizontal -1}
                               :turning-right {:horizontal 1}}
                              dt)
        players (:players next-world)]
    (is (= :left (get-in players [:turning-left :facing])))
    (is (= :right (get-in players [:turning-right :facing])))
    (is (= :left (get-in players [:coasting :facing])))
    (is (near? (get-in players [:turning-left :vx]) -22))
    (is (near? (get-in players [:turning-right :vx]) 22))
    (is (near? (get-in players [:coasting :vx]) 47.5))))

(deftest falling-jumping-landing-and-dropping-preserve-facing
  (let [ground {:id :ground :type :solid
                :x 0 :y 100 :width 100 :height 8}
        ledge {:id :ledge :type :semisolid
               :x 120 :y 100 :width 100 :height 8}
        world {:platforms [ground ledge]
               :players {:falling (assoc (player 250 20) :facing :left)
                         :jumping (assoc (player 50 84) :facing :left)
                         :landing (assoc (player 150 70)
                                         :facing :right :vy 560)
                         :dropping (assoc (player 180 84) :facing :right)}}
        next-world (game/step world
                              {:jumping {:jump? true}
                               :dropping {:drop? true}}
                              dt)
        players (:players next-world)]
    (testing "falling and jumping preserve the previous facing"
      (is (= :left (get-in players [:falling :facing])))
      (is (pos? (get-in players [:falling :vy])))
      (is (= :left (get-in players [:jumping :facing])))
      (is (neg? (get-in players [:jumping :vy]))))
    (testing "landing contact and drop-through preserve the previous facing"
      (is (= :right (get-in players [:landing :facing])))
      (is (near? (get-in players [:landing :y]) 84))
      (is (zero? (get-in players [:landing :vy])))
      (is (= :right (get-in players [:dropping :facing])))
      (is (= :ledge (get-in players [:dropping :drop-through-platform-id]))))))

(deftest landing-unlocks-direction-only-for-the-following-tick
  (let [platform {:id :floor :type :solid :x 0 :y 100 :width 100 :height 8}
        before (game/make-player "#22d3ee" 50 84)
        landed (game/step-player before [platform] {:horizontal -1} dt)
        controlled (game/step-player landed [platform] {:horizontal -1} dt)]
    (is (false? (:spawn-locked? landed)))
    (is (= :right (:facing landed)))
    (is (= :left (:facing controlled)))
    (is (neg? (:vx controlled)))))

(ns clojure-arena.jump-integration-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)

(defn near? [actual expected]
  (< (abs (- actual expected)) 1e-8))

(defn player-of [world]
  (fixtures/player-of world))

(defn grounded-world [overrides]
  (update-in fixtures/single-platform-world [:players :player-1]
          merge {:x 400 :y 264 :vx 0 :vy 0
                 :spawn-locked? false :air-jumps-left 1}
          overrides))

(defn landing-transition [world inputs]
  (loop [state world
         ticks-left 120]
    (if (zero? ticks-left)
      [state nil]
      (let [next-state (fixtures/step state inputs dt)]
        (if (game/grounded? (player-of next-state) (first (:platforms next-state)))
          [state next-state]
          (recur next-state (dec ticks-left)))))))

(defn run-until-grounded [world]
  (loop [state world
         ticks-left 120]
    (cond
      (game/grounded? (player-of state) (first (:platforms state))) state
      (zero? ticks-left) nil
      :else (recur (fixtures/step state {} dt) (dec ticks-left)))))

(deftest flat-support-is-center-based-and-includes-both-endpoints
  (let [platform (first (:platforms fixtures/single-platform-world))
        supported (fn [x]
                    (assoc (fixtures/player-of fixtures/single-platform-world) :x x :y 264 :vy 0))]
    (doseq [x [(:x platform) (+ (:x platform) (:width platform))]]
      (let [player (supported x)]
        (is (game/grounded? player platform))
        (is (boolean (game/supporting-platform player [platform])))))
    (doseq [player [(supported 154)
                    (assoc (supported 400) :y 264.000001)
                    (assoc (supported 400) :vy 1e-12)
                    (assoc (supported 400) :y 288)]]
      (is (not (game/grounded? player platform)))
      (is (not (game/supporting-platform player [platform]))))))

(deftest spawn-lock-blocks-held-controls-through-landing-tick
  (let [initial fixtures/single-platform-world
        [before-landing landing]
        (landing-transition initial {:horizontal 1})
        attempted-landing-jump
        (fixtures/step before-landing {:horizontal 1 :jump? true} dt)
        first-controlled-tick
        (fixtures/step attempted-landing-jump {:horizontal 1} dt)]
    (is (true? (get-in initial [:players :player-1 :spawn-locked?])))
    (is (true? (get-in before-landing [:players :player-1 :spawn-locked?])))
    (is (near? 400 (get-in before-landing [:players :player-1 :x])))
    (is (some? landing) "the held direction reaches a supported landing")
    (is (game/grounded? (player-of attempted-landing-jump)
                        (first (:platforms attempted-landing-jump))))
    (is (false? (get-in attempted-landing-jump [:players :player-1 :spawn-locked?])))
    (is (near? 400 (get-in attempted-landing-jump [:players :player-1 :x])))
    (is (zero? (get-in attempted-landing-jump [:players :player-1 :vy]))
        "the locked landing-tick jump is discarded")
    (is (pos? (get-in first-controlled-tick [:players :player-1 :vx]))
        "held horizontal input becomes active on the following tick")
    (is (not (neg? (get-in first-controlled-tick [:players :player-1 :vy])))
        "the rejected jump does not replay")))

(deftest ground-and-air-jump-cycle-restores-one-jump-on-landing
  (let [world (grounded-world {})
        ground-jump (fixtures/step world {:jump? true} dt)
        air-jump (fixtures/step ground-jump {:jump? true} dt)
        rejected-third (fixtures/step air-jump {:jump? true} dt)
        landed (run-until-grounded rejected-third)
        fresh-ground-jump (when landed (fixtures/step landed {:jump? true} dt))]
    (is (near? -395.5 (get-in ground-jump [:players :player-1 :vy])))
    (is (near? 254.1125 (get-in ground-jump [:players :player-1 :y])))
    (is (= 1 (get-in ground-jump [:players :player-1 :air-jumps-left]))
        "the ground jump preserves the air-jump allowance")
    (is (near? -395.5 (get-in air-jump [:players :player-1 :vy])))
    (is (near? 244.225 (get-in air-jump [:players :player-1 :y])))
    (is (= 0 (get-in air-jump [:players :player-1 :air-jumps-left])))
    (is (near? -371 (get-in rejected-third [:players :player-1 :vy]))
        "a third request is rejected while gravity continues")
    (is (= 0 (get-in rejected-third [:players :player-1 :air-jumps-left])))
    (is (some? landed))
    (when landed
      (is (game/grounded? (player-of landed) (first (:platforms landed))))
      (is (= 1 (get-in landed [:players :player-1 :air-jumps-left]))
          "only supported top landing restores the allowance")
      (is (near? -395.5 (get-in fresh-ground-jump [:players :player-1 :vy])))
      (is (= 1 (get-in fresh-ground-jump [:players :player-1 :air-jumps-left]))))))

(deftest exhausted-air-jump-request-on-landing-tick-is-not-buffered
  (let [world (grounded-world {:y 260 :vy 150 :air-jumps-left 0})
        landing-tick (fixtures/step world {:jump? true} dt)
        following-tick (fixtures/step landing-tick {} dt)]
    (is (game/grounded? (player-of landing-tick) (first (:platforms landing-tick))))
    (is (near? 264 (get-in landing-tick [:players :player-1 :y])))
    (is (zero? (get-in landing-tick [:players :player-1 :vy])))
    (is (= 1 (get-in landing-tick [:players :player-1 :air-jumps-left])))
    (is (game/grounded? (player-of following-tick) (first (:platforms following-tick))))
    (is (zero? (get-in following-tick [:players :player-1 :vy]))
        "landing refills eligibility but does not replay the rejected request")))

(deftest side-underside-and-corner-contacts-do-not-refill-exhausted-state
  (let [base (grounded-world {:air-jumps-left 0})
        cases [[:side (update-in base [:players :player-1] merge {:x 149 :y 288 :vx 220 :vy 0})
                {:horizontal 1}]
               [:underside (update-in base [:players :player-1] merge {:y 320 :vy -1000})
                {}]
               [:corner (update-in base [:players :player-1] merge {:x 158 :y 267 :vx 220 :vy 500})
                {:horizontal 1}]]]
    (doseq [[label initial inputs] cases]
      (let [world (fixtures/step initial inputs dt)]
        (testing (name label)
          (is (= 0 (get-in world [:players :player-1 :air-jumps-left])))
          (is (not (game/grounded? (player-of world) (first (:platforms world))))))))))

(deftest edge-departure-keeps-controls-unlocked-and-air-jump-available
  (let [world (grounded-world {:x 629.75 :vx 220})
        departed (fixtures/step world {:horizontal 1} dt)
        edge-jump (fixtures/step departed {:jump? true} dt)]
    (is (> (get-in departed [:players :player-1 :x]) 630))
    (is (not (game/grounded? (player-of departed) (first (:platforms departed)))))
    (is (false? (get-in departed [:players :player-1 :spawn-locked?])))
    (is (= 1 (get-in departed [:players :player-1 :air-jumps-left])))
    (is (near? -395.5 (get-in edge-jump [:players :player-1 :vy])))
    (is (= 0 (get-in edge-jump [:players :player-1 :air-jumps-left])))))

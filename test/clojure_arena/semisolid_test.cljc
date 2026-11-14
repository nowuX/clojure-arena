(ns clojure-arena.semisolid-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def semisolid
  {:id :test-semisolid
   :type :semisolid
   :x 100
   :y 200
   :width 100
   :height 8})

(def player-radius (get-in game/initial-world [:players :player-1 :radius]))
(def dt 0.025)

(defn player-at [state]
  (merge (fixtures/player-of game/initial-world)
         {:x 150
          :y (- (:y semisolid) player-radius)
          :vx 0
          :vy 0
          :radius player-radius
          :spawn-locked? true
          :air-jumps-left 1}
         state))

(defn scene [platforms player-state]
  {:platforms (vec platforms)
   :players {:player-1 (player-at player-state)}})

(defn close-to? [actual expected tolerance]
  (<= (- expected tolerance) actual (+ expected tolerance)))

(defn sqrt [value]
  #?(:clj (Math/sqrt value)
     :cljs (js/Math.sqrt value)))

(defn rectangle-clearance [{player-x :x player-y :y}
                           {left :x top :y width :width height :height}]
  (let [right (+ left width)
        bottom (+ top height)
        dx (max 0 (- left player-x) (- player-x right))
        dy (max 0 (- top player-y) (- player-y bottom))]
    (sqrt (+ (* dx dx) (* dy dy)))))

(deftest fast-descending-sweep-lands-at-the-crossing-time
  (let [player (player-at {:x 90 :y 155 :vx 200 :vy 300
                           :spawn-locked? true :air-jumps-left 0})
        landed (game/collide-platforms player [semisolid] 0.1)]
    (is (close-to? (:x landed) 110 1e-8))
    (is (close-to? (:y landed) (- (:y semisolid) player-radius) 1e-8))
    (is (zero? (:vy landed)))
    (is (== 200 (:vx landed)))
    (is (true? (:spawn-locked? player)))))

(deftest a-rise-through-the-platform-can-return-and-land
  (let [world (scene [semisolid]
                     {:x 150 :y 235 :vy -1000
                      :spawn-locked? true :air-jumps-left 0})
        states (take 120 (iterate #(fixtures/step % {} dt) world))
        rose-above? (some #(and (< (get-in % [:players :player-1 :y])
                                   (- (:y semisolid) player-radius))
                                (neg? (get-in % [:players :player-1 :vy])))
                          states)
        landed (first (filter #(game/grounded? (fixtures/player-of %) semisolid) states))]
    (is rose-above?)
    (is (some? landed))
    (when landed
      (is (== (- (:y semisolid) player-radius) (get-in landed [:players :player-1 :y])))
      (is (false? (get-in landed [:players :player-1 :spawn-locked?])))
      (is (= 1 (get-in landed [:players :player-1 :air-jumps-left]))))))

(deftest rising-side-and-underside-paths-pass-through
  (let [rising (game/collide-platforms
                 (player-at {:x 92 :y 230 :vy -1000}) [semisolid] 0.1)
        side (game/collide-platforms
               (player-at {:x 60 :y 204 :vx 800 :vy 0}) [semisolid] 0.1)
        underside (game/collide-platforms
                    (player-at {:x 150 :y 240 :vy -1000}) [semisolid] 0.1)]
    (is (< (:y rising) (- (:y semisolid) player-radius)))
    (is (== -1000 (:vy rising)))
    (is (== 140 (:x side)))
    (is (== 800 (:vx side)))
    (is (== 140 (:y underside)))
    (is (== -1000 (:vy underside)))))

(deftest only-the-inclusive-flat-span-counts-as-ground-support
  (doseq [x [(:x semisolid)
             (+ (:x semisolid) (/ (:width semisolid) 2))
             (+ (:x semisolid) (:width semisolid))]]
    (let [player (player-at {:x x :vy 0})]
      (is (game/grounded? player semisolid))
      (is (= semisolid (game/supporting-platform player [semisolid])))))
  (doseq [x [(dec (:x semisolid))
             (inc (+ (:x semisolid) (:width semisolid)))]]
    (let [player (player-at {:x x :vy 0})]
      (is (not (game/grounded? player semisolid)))
      (is (nil? (game/supporting-platform player [semisolid]))))))

(deftest descending-upper-corner-contact-does-not-unlock-or-refill
  (doseq [[x direction] [[(- (:x semisolid) 8) -1]
                         [(+ (:x semisolid) (:width semisolid) 8) 1]]]
    (let [world (scene [semisolid]
                       {:x x :y 160 :vy 600
                        :spawn-locked? true :air-jumps-left 0})
          result (fixtures/step world {} 0.1)
          player (fixtures/player-of result)]
      (is (>= (rectangle-clearance player semisolid) (- player-radius 1e-6)))
      (is (= direction (if (neg? (:vx player)) -1 1)))
      (is (true? (:spawn-locked? player)))
      (is (zero? (:air-jumps-left player)))
      (is (not (game/grounded? player semisolid))))))

(deftest slow-and-fast-departures-round-both-ends-without-penetration
  (let [left (:x semisolid)
        right (+ left (:width semisolid))
        cases [[:left-slow (+ left 0.25) -5 -1 :left]
               [:left-fast (+ left 5) -200 -1 :left]
               [:right-slow (- right 0.25) 5 1 :right]
               [:right-fast (- right 5) 200 1 :right]]]
    (doseq [[label start-x vx horizontal direction] cases]
      (testing (name label)
        (let [world (scene [semisolid]
                           {:x start-x :vx vx :vy 0 :spawn-locked? false})
              states (take 31
                           (iterate #(fixtures/step % {:horizontal horizontal} dt) world))
              players (map fixtures/player-of states)
              edge (if (= direction :left) left right)
              outside? (fn [player]
                         (if (= direction :left)
                           (< (:x player) edge)
                           (> (:x player) edge)))
              rounding? (some (fn [player]
                                (and (outside? player)
                                     (> (:y player) (- (:y semisolid) player-radius))
                                     (< (:y player)
                                        (+ (:y semisolid) (:height semisolid)
                                           player-radius))))
                              players)
              final-player (last players)]
          (is (every? #(>= (rectangle-clearance % semisolid)
                           (- player-radius 1e-5))
                      players))
          (is rounding?)
          (is (if (= direction :left)
                (< (:x final-player) (- left player-radius))
                (> (:x final-player) (+ right player-radius))))
          (is (> (:y final-player)
                 (+ (:y semisolid) (:height semisolid) player-radius))))))))

(deftest a-fast-fall-chooses-the-earliest-semisolid-in-either-order
  (let [lower (assoc semisolid :id :lower :y 300)
        orders [[semisolid lower] [lower semisolid]]
        results (mapv (fn [platforms]
                        (fixtures/step (scene platforms
                                          {:x 150 :y 80 :vy 10000
                                           :spawn-locked? true :air-jumps-left 0})
                                   {} 0.05))
                      orders)]
    (is (= (get-in (first results) [:players :player-1 :y])
           (get-in (second results) [:players :player-1 :y])))
    (doseq [result results]
      (is (== (- (:y semisolid) player-radius) (get-in result [:players :player-1 :y])))
      (is (zero? (get-in result [:players :player-1 :vy])))
      (is (= 1 (get-in result [:players :player-1 :air-jumps-left]))))))

(deftest a-semisolid-flat-top-supports-the-ordinary-ground-jump
  (let [world (scene [semisolid]
                     {:x 150 :y (- (:y semisolid) player-radius)
                      :spawn-locked? false :air-jumps-left 0})
        jumped (fixtures/step world {:jump? true} dt)]
    (is (= -395.5 (get-in jumped [:players :player-1 :vy])))
    (is (= 0 (get-in jumped [:players :player-1 :air-jumps-left])))
    (is (< (get-in jumped [:players :player-1 :y]) (- (:y semisolid) player-radius)))))

(deftest a-real-scene-ground-jump-rises-through-and-lands-on-the-left-platform
  (let [world (update-in game/initial-world [:players :player-1] assoc
                      :x 260 :y 264 :vx 0 :vy 0 :spawn-locked? false :air-jumps-left 0)
        left (first (filter #(= :left (:id %)) (:platforms world)))
        jumped (fixtures/step world {:jump? true} dt)
        states (take 120 (iterate #(fixtures/step % {} dt) jumped))
        above (some #(< (get-in % [:players :player-1 :y]) (- (:y left) player-radius)) states)
        landed (first (filter #(game/grounded? (fixtures/player-of %) left) states))]
    (is above)
    (is (some? landed))
    (when landed
      (is (= 1 (get-in landed [:players :player-1 :air-jumps-left])))
      (is (false? (get-in landed [:players :player-1 :spawn-locked?]))))))

(deftest a-semisolid-landing-can-continue-into-a-solid-wall
  (let [floor (assoc semisolid :width 200)
        wall {:id :wall :type :solid :x 260 :y 150 :width 16 :height 100}
        incoming (player-at {:x 230 :y 170 :vx 220 :vy 200 :spawn-locked? false
                             :air-jumps-left 0})
        a (game/collide-platforms incoming [floor wall] 0.1)
        b (game/collide-platforms incoming [wall floor] 0.1)]
    (is (= a b))
    (is (close-to? (:x a) 244 1e-8))
    (is (close-to? (:y a) 184 1e-8))
    (is (zero? (:vx a)))
    (is (zero? (:vy a)))
    (is (= 1 (:air-jumps-left a)))))

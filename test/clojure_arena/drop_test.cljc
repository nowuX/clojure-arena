(ns clojure-arena.drop-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def upper-semisolid
  {:id :upper
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
          :y (- (:y upper-semisolid) player-radius)
          :vx 0
          :vy 0
          :radius player-radius
          :spawn-locked? false
          :air-jumps-left 1
          :drop-through-platform-id nil}
         state))

(defn scene [platforms player-state]
  {:platforms (vec platforms)
   :players {:player-1 (player-at player-state)}})

(defn close-to? [actual expected tolerance]
  (<= (- expected tolerance) actual (+ expected tolerance)))

(defn stepped [world inputs]
  (game/step world inputs dt))

(deftest drop-beats-jump-adds-no-impulse-and-preserves-air-allowance
  (is (nil? (get-in game/initial-world [:players :player-1 :drop-through-platform-id])))
  (doseq [air-jumps-left [0 1]]
    (let [world (scene [upper-semisolid] {:air-jumps-left air-jumps-left})
          dropped (stepped world {:drop? true :jump? true})
          player (fixtures/player-of dropped)]
      (is (= :upper (:drop-through-platform-id player)))
      (is (== 150 (:x player)))
      (is (close-to? (:y player) 184.6125 1e-8))
      (is (= 24.5 (:vy player)))
      (is (= air-jumps-left (:air-jumps-left player)))
      (is (= 184 (:y (fixtures/player-of world)))))))

(deftest a-solid-support-rejects-drop-and-keeps-the-ground-jump
  (let [solid (assoc upper-semisolid :type :solid)
        world (scene [solid] {})
        result (stepped world {:drop? true :jump? true})]
    (is (nil? (get-in result [:players :player-1 :drop-through-platform-id])))
    (is (= -395.5 (get-in result [:players :player-1 :vy])))
    (is (= 1 (get-in result [:players :player-1 :air-jumps-left])))))

(deftest airborne-locked-and-corner-only-requests-are-ignored
  (let [airborne (stepped (scene [upper-semisolid] {:y 100}) {:drop? true})
        locked (stepped (scene [upper-semisolid] {:spawn-locked? true})
                        {:drop? true})
        arc-offset 8
        arc-rise (#?(:clj Math/sqrt :cljs js/Math.sqrt)
                  (- (* player-radius player-radius) (* arc-offset arc-offset)))
        corner (stepped (scene [upper-semisolid]
                               {:x (- (:x upper-semisolid) arc-offset)
                                :y (- (:y upper-semisolid) arc-rise)})
                        {:drop? true})]
    (is (nil? (get-in airborne [:players :player-1 :drop-through-platform-id])))
    (is (= 24.5 (get-in airborne [:players :player-1 :vy])))
    (is (nil? (get-in locked [:players :player-1 :drop-through-platform-id])))
    (is (nil? (get-in corner [:players :player-1 :drop-through-platform-id])))))

(deftest drop-is-available-from-every-semisolid-in-the-initial-scene
  (let [platforms (:platforms game/initial-world)
        semisolids (filter #(= :semisolid (:type %)) platforms)]
    (is (= 3 (count semisolids)))
    (doseq [platform semisolids]
      (testing (name (:id platform))
        (let [x (+ (:x platform) (/ (:width platform) 2))
              world (scene platforms
                           {:x x :y (- (:y platform) player-radius)})
              result (stepped world {:drop? true})]
          (is (= (:id platform)
                 (get-in result [:players :player-1 :drop-through-platform-id])))
          (is (> (get-in result [:players :player-1 :y])
                 (- (:y platform) player-radius))))))))

(deftest only-the-dropped-platform-is-ignored-while-lower-platforms-catch
  (doseq [lower [(assoc upper-semisolid :id :lower-solid :y 270 :type :solid)
                 (assoc upper-semisolid :id :lower-semi :y 270)]]
    (doseq [platforms [[upper-semisolid lower]
                       [lower upper-semisolid]]]
      (let [world (scene platforms {})
            result (game/step world {:drop? true} 0.35)
            player (fixtures/player-of result)]
        (is (== (- (:y lower) player-radius) (:y player)))
        (is (zero? (:vy player)))
        (is (nil? (:drop-through-platform-id player)))
        (is (= 1 (:air-jumps-left player)))))))

(defn partly-below? [world]
  (let [top (- (get-in world [:players :player-1 :y]) player-radius)]
    (and (>= top (:y upper-semisolid))
         (< top (+ (:y upper-semisolid) (:height upper-semisolid))))))

(deftest the-exception-lasts-until-the-whole-circle-clears-the-bottom
  (let [world (scene [upper-semisolid] {})
        dropped (stepped world {:drop? true})
        states (take 20 (iterate #(stepped % {}) dropped))
        partial (first (filter partly-below? states))
        fully-below (first (filter #(>= (- (get-in % [:players :player-1 :y]) player-radius)
                                         (+ (:y upper-semisolid)
                                            (:height upper-semisolid)))
                                   states))]
    (is (= :upper (get-in partial [:players :player-1 :drop-through-platform-id])))
    (is (some? fully-below))
    (when fully-below
      (is (nil? (get-in fully-below [:players :player-1 :drop-through-platform-id]))
          "the exception clears after the circle's top passes the bottom face"))))

(deftest stale-exceptions-clear-below-above-or-after-platform-removal
  (let [below (stepped (scene [upper-semisolid]
                              {:y (+ (:y upper-semisolid)
                                     (:height upper-semisolid)
                                     player-radius)
                               :drop-through-platform-id :upper})
                       {})
        above (stepped (scene [upper-semisolid]
                              {:y (- (:y upper-semisolid) player-radius 1)
                               :drop-through-platform-id :upper})
                       {})
        missing (stepped (scene [] {:drop-through-platform-id :upper}) {})]
    (is (nil? (get-in below [:players :player-1 :drop-through-platform-id])))
    (is (nil? (get-in above [:players :player-1 :drop-through-platform-id])))
    (is (nil? (get-in missing [:players :player-1 :drop-through-platform-id])))))

(deftest jumping-back-during-partial-traversal-can-land-on-the-same-platform
  (let [world (scene [upper-semisolid] {})
        dropped (stepped world {:drop? true})
        falling-states (take 20 (iterate #(stepped % {}) dropped))
        partial (first (filter partly-below? falling-states))
        jumped (stepped partial {:jump? true})
        rising-states (take 40 (iterate #(stepped % {}) jumped))
        still-crossing (take-while #(>= (+ (get-in % [:players :player-1 :y]) player-radius)
                                         (:y upper-semisolid))
                                   rising-states)
        above (first (filter #(< (+ (get-in % [:players :player-1 :y]) player-radius)
                                 (:y upper-semisolid))
                             rising-states))
        landed (first (filter #(game/grounded? (fixtures/player-of %) upper-semisolid)
                              rising-states))]
    (is (some? partial))
    (is (= :upper (get-in partial [:players :player-1 :drop-through-platform-id])))
    (is (= 0 (get-in jumped [:players :player-1 :air-jumps-left])))
    (is (seq still-crossing))
    (is (every? #(= :upper (get-in % [:players :player-1 :drop-through-platform-id]))
                still-crossing))
    (is (some? above))
    (when above
      (is (nil? (get-in above [:players :player-1 :drop-through-platform-id]))))
    (is (some? landed))
    (when landed
      (is (= 1 (get-in landed [:players :player-1 :air-jumps-left]))))))

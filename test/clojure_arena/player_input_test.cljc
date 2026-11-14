(ns clojure-arena.player-input-test
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
   :color "#ffffff"})

(defn- near? [actual expected]
  #?(:clj (< (Math/abs (- actual expected)) 1e-8)
     :cljs (< (js/Math.abs (- actual expected)) 1e-8)))

(deftest step-player-uses-explicit-input-and-dt-without-mutating-arguments
  (let [before (player 10 20)
        inputs {:horizontal 1}
        next-player (game/step-player before [] inputs 0.01)]
    (is (near? (:x next-player) 10.088))
    (is (near? (:vx next-player) 8.8))
    (is (near? (:y next-player) 20.098))
    (is (near? (:vy next-player) 9.8))
    (is (= 10 (:x before)))
    (is (= 0 (:vx before)))
    (is (= {:horizontal 1} inputs))))

(deftest players-advance-simultaneously-with-their-own-inputs
  (let [world {:platforms []
               :players {:left (player 100 20)
                         :right (player 200 20)
                         :neutral (player 300 20)}}
        next-world (game/step world
                              {:left {:horizontal -1}
                               :right {:horizontal 1}}
                              dt)]
    (is (near? (get-in next-world [:players :left :x]) 99.45))
    (is (near? (get-in next-world [:players :left :vx]) -22))
    (is (near? (get-in next-world [:players :right :x]) 200.55))
    (is (near? (get-in next-world [:players :right :vx]) 22))
    (is (near? (get-in next-world [:players :neutral :x]) 300))
    (is (near? (get-in next-world [:players :neutral :y]) 20.6125))
    (is (near? (get-in next-world [:players :neutral :vy]) 24.5))))

(deftest player-and-input-map-order-does-not-change-results
  (let [entries [[:alpha (player 100 20)]
                 ["bravo" (player 200 20)]
                 [[:charlie 3] (player 300 20)]]
        input-entries [[:alpha {:horizontal 1}]
                       ["bravo" {:horizontal -1}]
                       [[:charlie 3] nil]]
        make-world (fn [ordered-players]
                     {:platforms []
                      :players (into (array-map) ordered-players)})
        make-inputs (fn [ordered-inputs]
                      (into (array-map) ordered-inputs))
        forward (game/step (make-world entries)
                           (make-inputs input-entries)
                           dt)
        reversed (game/step (make-world (reverse entries))
                            (make-inputs (reverse input-entries))
                            dt)]
    (is (= (:players forward) (:players reversed)))
    (is (= #{:alpha "bravo" [:charlie 3]}
           (set (keys (:players forward)))))
    (is (near? (get-in forward [:players :alpha :vx]) 22))
    (is (near? (get-in forward [:players "bravo" :vx]) -22))))

(deftest a-neutral-input-still-advances-one-physics-step
  (let [world {:platforms []
               :players {:coasting (assoc (player 100 20) :vx 110)}}
        next-world (game/step world {} dt)
        coasting (get-in next-world [:players :coasting])]
    (is (near? (:x coasting) 102.0625))
    (is (near? (:vx coasting) 82.5))
    (is (near? (:y coasting) 20.6125))
    (is (near? (:vy coasting) 24.5))
    (is (= 100 (get-in world [:players :coasting :x])))))

(deftest missing-nil-and-unknown-inputs-preserve-the-player-set
  (let [world {:platforms []
               :players {:alpha (player 100 20)
                         :beta (player 200 20)}}
        next-world (game/step world
                              {:alpha nil
                               :intruder {:horizontal -1}}
                              dt)]
    (is (= #{:alpha :beta} (set (keys (:players next-world)))))
    (is (not (contains? (:players next-world) :player-1)))
    (is (not (contains? (:players next-world) :intruder)))
    (is (near? (get-in next-world [:players :alpha :x]) 100))
    (is (near? (get-in next-world [:players :alpha :y]) 20.6125))
    (is (near? (get-in next-world [:players :beta :y]) 20.6125))))

(deftest spawn-lock-jump-and-drop-through-state-is-independent-per-player
  (let [ledge {:id :shared-ledge :type :semisolid
               :x 0 :y 100 :width 300 :height 8}
        world {:platforms [ledge]
               :players {:locked (assoc (player 30 84) :spawn-locked? true)
                         :jumper (player 150 160)
                         :dropper (player 250 84)}}
        next-world (game/step world
                              {:locked {:horizontal 1 :jump? true :drop? true}
                               :jumper {:jump? true}
                               :dropper {:drop? true}}
                              dt)
        locked (get-in next-world [:players :locked])
        jumper (get-in next-world [:players :jumper])
        dropper (get-in next-world [:players :dropper])]
    (testing "a locked player consumes no control or drop exception"
      (is (near? (:x locked) 30))
      (is (near? (:y locked) 84))
      (is (false? (:spawn-locked? locked))
          "landing unlocks the player for a later input tick")
      (is (= 1 (:air-jumps-left locked)))
      (is (nil? (:drop-through-platform-id locked))))
    (testing "an airborne player spends only their own air jump"
      (is (near? (:vy jumper) -395.5))
      (is (near? (:y jumper) 150.1125))
      (is (= 0 (:air-jumps-left jumper))))
    (testing "dropping through one semisolid does not change other players"
      (is (near? (:y dropper) 84.6125))
      (is (near? (:vy dropper) 24.5))
      (is (= :shared-ledge (:drop-through-platform-id dropper))))))

(deftest nil-input-and-empty-player-maps-remain-neutral
  (let [world {:platforms [] :label :untouched
               :players {:alpha (player 100 20)}}
        empty-world (assoc world :players {})]
    (is (= (game/step world {} dt) (game/step world nil dt)))
    (is (= :untouched (:label (game/step world nil dt))))
    (is (= empty-world (game/step empty-world {:intruder {:jump? true}} dt)))))

(deftest all-local-fixtures-land-and-unlock-independently
  (let [landed (nth (iterate #(game/step % {} dt) game/initial-world) 40)
        players (:players landed)]
    (is (= #{:player-1 :player-2 :player-3 :player-4} (set (keys players))))
    (is (every? #(false? (:spawn-locked? %)) (vals players)))
    (is (= [264.0 194.0 194.0 264.0]
           (mapv #(double (get-in players [% :y]))
                 [:player-1 :player-2 :player-3 :player-4])))
    (is (every? #(= 1 (:air-jumps-left %)) (vals players)))
    (is (every? #(nil? (:drop-through-platform-id %)) (vals players)))))

(ns clojure-arena.stock-order-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def blast-bounds {:left 0 :right 100 :top 0 :bottom 100})
(def dt 0.1)

(defn- player-at [x y radius]
  (assoc (game/make-player "#ffffff" x y) :radius radius))

(defn- scene [players]
  {:blast-bounds blast-bounds
   :platforms []
   :players players})

(deftest factory-players-are-active-and-only-active-players-step
  (let [fresh (assoc (player-at 20 50 10) :spawn-locked? false)
        respawning (assoc (player-at -200 50 10)
                          :stocks 2 :lifecycle :respawning :respawn-seconds-left 3
                          :vx 200 :vy 80 :knockback-vx 20 :knockback-vy 10)
        eliminated (assoc (player-at 200 50 10)
                          :stocks 0 :lifecycle :eliminated
                          :vx -100 :vy 60)
        world (scene {:fresh fresh :respawning respawning :eliminated eliminated})
        stepped (game/step world
                           {:fresh {:horizontal 1 :jump? true :attack? true}
                            :respawning {:horizontal -1 :jump? true :drop? true :attack? true}
                            :eliminated {:horizontal 1 :jump? true :attack? true}}
                           dt)]
    (is (game/active-player? fresh))
    (is (not (game/active-player? respawning)))
    (is (not (game/active-player? eliminated)))
    (is (> (get-in stepped [:players :fresh :x]) (:x fresh)))
    (is (= (dissoc respawning :respawn-seconds-left)
           (dissoc (get-in stepped [:players :respawning]) :respawn-seconds-left)))
    (is (= eliminated (get-in stepped [:players :eliminated])))
    (is (= 2 (get-in stepped [:players :respawning :stocks])))
    (is (= 0 (get-in stepped [:players :eliminated :stocks])))))

(deftest one-ring-out-consumes-one-stock-and-clears-transient-gameplay-state
  (let [semi {:id :semi :type :semisolid :x -120 :y 40 :width 20 :height 8
              :color "#9c9c9c"}
        departing (-> (player-at -97 50 16)
                      (assoc :stocks 2 :lifecycle :active
                             :vx 30 :vy -20 :knockback-vx 12 :knockback-vy -8
                             :spawn-locked? false :air-jumps-left 0
                             :drop-through-platform-id :semi
                             :melee-cooldown-left 0.2
                             :melee-strike-id 2 :melee-resolved-strike-id 1
                             :melee-target-ids #{:survivor}
                             :accumulated-percentage 45))
        world (assoc (scene {:departing departing
                             :survivor (player-at 20 50 16)})
                     :platforms [semi])
        lost (game/step world
                        {:departing {:horizontal -1 :jump? true :drop? true :attack? true}}
                        0)
        player (get-in lost [:players :departing])]
    (is (= 1 (:stocks player)))
    (is (= :respawning (:lifecycle player)))
    (is (= [0 0 0 0]
           ((juxt :vx :vy :knockback-vx :knockback-vy) player)))
    (is (true? (:spawn-locked? player)))
    (is (= 1 (:air-jumps-left player)))
    (is (nil? (:drop-through-platform-id player)))
    (is (zero? (:melee-cooldown-left player)))
    (is (= (:melee-strike-id player) (:melee-resolved-strike-id player)))
    (is (= #{} (:melee-target-ids player)))
    (is (= 45 (:accumulated-percentage player)))
    (is (= (get-in world [:players :survivor])
           (get-in lost [:players :survivor])))))

(deftest repeated-inactive-ticks-do-not-consume-more-stocks
  (let [departing (assoc (player-at -97 50 16)
                         :stocks 3 :lifecycle :active :accumulated-percentage 35)
        initial (scene {:departing departing})
        first-loss (game/step initial {} 0)
        after-first (get-in first-loss [:players :departing])
        repeated (nth (iterate #(game/step % {} dt) first-loss) 5)
        second-loss (game/step (assoc-in first-loss [:players :departing]
                                         (assoc after-first :lifecycle :active))
                               {} 0)
        third-loss (game/step (assoc-in second-loss
                                        [:players :departing :lifecycle]
                                        :active)
                              {} 0)
        eliminated (get-in third-loss [:players :departing])
        repeated-elimination (game/step third-loss {} dt)]
    (is (= 2 (:stocks after-first)))
    (is (= :respawning (:lifecycle after-first)))
    (is (= (dissoc after-first :respawn-seconds-left)
           (dissoc (get-in repeated [:players :departing]) :respawn-seconds-left)))
    (is (= 1 (get-in second-loss [:players :departing :stocks])))
    (is (= :respawning (get-in second-loss [:players :departing :lifecycle])))
    (is (= 0 (:stocks eliminated)))
    (is (= :eliminated (:lifecycle eliminated)))
    (is (= 35 (:accumulated-percentage eliminated)))
    (is (= eliminated (get-in repeated-elimination [:players :departing])))))

(deftest ring-out-is-resolved-before-an-escaping-attacker-can-hit
  (let [attacker (-> (player-at 90 50 10)
                     (assoc :stocks 3 :lifecycle :active
                            :facing :left :spawn-locked? false :vx 400))
        target (assoc (player-at 70 50 10) :accumulated-percentage 20)
        result (game/step (scene {:attacker attacker :target target})
                          {:attacker {:attack? true}}
                          dt)
        attacker-after (get-in result [:players :attacker])]
    (is (game/outside-arena? attacker-after blast-bounds)
        "the fast attacker has crossed the right blast edge")
    (is (= 2 (:stocks attacker-after)))
    (is (= :respawning (:lifecycle attacker-after)))
    (is (= #{} (:melee-target-ids attacker-after)))
    (is (= (:melee-strike-id attacker-after)
           (:melee-resolved-strike-id attacker-after)))
    (is (= 20 (get-in result [:players :target :accumulated-percentage])))))

(deftest a-target-that-exits-in-the-tick-cannot-be-hit
  (let [attacker (assoc (player-at 60 50 10) :spawn-locked? false)
        escaping-target (assoc (player-at 90 50 10)
                               :vx 400 :stocks 2 :lifecycle :active
                               :accumulated-percentage 35)
        result (game/step (scene {:attacker attacker :target escaping-target})
                          {:attacker {:attack? true}}
                          dt)
        attacker-after (get-in result [:players :attacker])
        target-after (get-in result [:players :target])]
    (is (= 1 (:melee-strike-id attacker-after)))
    (is (= #{} (:melee-target-ids attacker-after)))
    (is (= 1 (:stocks target-after)))
    (is (= :respawning (:lifecycle target-after)))
    (is (= 35 (:accumulated-percentage target-after)))
    (is (= [0 0 0 0]
           ((juxt :vx :vy :knockback-vx :knockback-vy) target-after)))))

(deftest inactive-attackers-and-targets-cannot-apply-melee-hits
  (let [inactive-attacker (assoc (player-at 20 50 10)
                                 :stocks 2 :lifecycle :respawning
                                 :melee-strike-id 1 :melee-resolved-strike-id 0
                                 :melee-target-ids #{:active-target})
        active-attacker (assoc (player-at 50 50 10)
                               :spawn-locked? false
                               :melee-strike-id 1 :melee-resolved-strike-id 0
                               :melee-target-ids #{:inactive-target})
        inactive-target (assoc (player-at 60 50 10)
                               :stocks 0 :lifecycle :eliminated
                               :accumulated-percentage 60)
        active-target (player-at 30 50 10)
        players {:inactive-attacker inactive-attacker
                 :active-attacker active-attacker
                 :inactive-target inactive-target
                 :active-target active-target}
        result (game/apply-melee-hits players)]
    (is (= #{} (game/melee-target-ids players :inactive-attacker)))
    (is (= #{} (game/melee-target-ids players :active-attacker)))
    (is (= inactive-attacker (get result :inactive-attacker)))
    (is (= inactive-target (get result :inactive-target)))
    (is (= active-target (get result :active-target)))
    (is (= 1 (get-in result [:active-attacker :melee-resolved-strike-id])))))

(deftest simultaneous-ring-outs-do-not-depend-on-player-map-order
  (let [alpha (assoc (player-at -97 30 16) :stocks 3 :lifecycle :active)
        bravo (assoc (player-at 120 30 16) :stocks 2 :lifecycle :active)
        alpha-first (game/step (scene (array-map :alpha alpha :bravo bravo)) {} 0)
        bravo-first (game/step (scene (array-map :bravo bravo :alpha alpha)) {} 0)]
    (is (= alpha-first bravo-first))
    (is (= {:alpha [2 :respawning]
            :bravo [1 :respawning]}
           (into {} (map (fn [[player-id player]]
                           [player-id [(:stocks player) (:lifecycle player)]])
                         (:players alpha-first)))))))

(deftest worlds-without-blast-bounds-do-not-consume-a-stock
  (let [player (assoc (player-at -10000 50 16) :stocks 2 :lifecycle :active)
        result (game/step {:platforms [] :players {:outside player}} {} 0)]
    (is (= player (get-in result [:players :outside])))))

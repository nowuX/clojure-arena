(ns clojure-arena.fresh-respawn-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def bounds {:left -1000 :right 1000 :top -500 :bottom 500})
(def floor {:id :floor :type :solid :x 0 :y 100 :width 880 :height 16})

(defn- scene [players]
  {:blast-bounds bounds :platforms [floor] :players players})

(defn- ticks [world n dt]
  (nth (iterate #(game/step % {} dt) world) n))

(defn- close-to? [actual expected]
  #?(:clj (< (Math/abs (- actual expected)) 1e-8)
     :cljs (< (js/Math.abs (- actual expected)) 1e-8)))

(deftest player-factory-records-its-original-spawn-coordinates
  (let [player (game/make-player "#4ade80" 320 20)]
    (is (= [320 20] ((juxt :spawn-x :spawn-y) player)))
    (is (= "#4ade80" (:color player)))))

(deftest respawn-rebuilds-a-clean-body-on-the-120th-later-tick
  (let [respawning (-> (game/make-player "#4ade80" 15 40)
                       (assoc :x -300 :y 250
                              :spawn-x 320 :spawn-y 20
                              :stocks 2
                              :lifecycle :respawning
                              :respawn-seconds-left 3
                              :vx 75 :vy -35
                              :knockback-vx 42 :knockback-vy -18
                              :facing :left
                              :spawn-locked? false
                              :air-jumps-left 0
                              :drop-through-platform-id :floor
                              :melee-cooldown-left 0.13
                              :melee-strike-id 7
                              :melee-resolved-strike-id 6
                              :melee-target-ids #{:observer}
                              :accumulated-percentage 85))
        observer (-> (game/make-player "#e11d48" 700 84)
                     (assoc :stocks 3 :spawn-locked? false))
        initial (scene {:runner respawning :observer observer})
        before-expiry (ticks initial 119 0.025)
        at-expiry (game/step before-expiry
                             {:runner {:horizontal 1 :jump? true
                                       :drop? true :attack? true}}
                             0.025)
        fresh (get-in at-expiry [:players :runner])]
    (testing "the final countdown tick returns a fresh player at the stored spawn"
      (is (= :respawning (get-in before-expiry [:players :runner :lifecycle])))
      (is (close-to? 0.025
                     (get-in before-expiry [:players :runner :respawn-seconds-left])))
      (is (false? (game/active-player? (get-in before-expiry [:players :runner]))))
      (is (true? (game/active-player? fresh)))
      (is (= 2 (:stocks fresh)))
      (is (= (assoc (game/make-player "#4ade80" 320 20) :stocks 2) fresh))
      (is (= "#4ade80" (:color fresh)))
      (is (= [320 20] ((juxt :x :y) fresh))
          "the new body receives no physics on its return tick")
      (is (= [0 0 0 0]
             ((juxt :vx :vy :knockback-vx :knockback-vy) fresh)))
      (is (= :right (:facing fresh)))
      (is (true? (:spawn-locked? fresh)))
      (is (= 1 (:air-jumps-left fresh)))
      (is (nil? (:drop-through-platform-id fresh)))
      (is (= [0 0 0 #{}]
             ((juxt :melee-cooldown-left :melee-strike-id
                    :melee-resolved-strike-id :melee-target-ids) fresh)))
      (is (zero? (:accumulated-percentage fresh)))
      (is (= #{:runner :observer} (set (keys (:players at-expiry)))))
      (is (= (get-in (ticks (scene {:observer observer}) 120 0.025) [:players :observer])
             (get-in at-expiry [:players :observer]))
          "respawning one player leaves another grounded player unchanged"))
    (testing "the input pulse on the return tick is discarded and controls stay locked"
      (is (= [0 0] ((juxt :melee-strike-id :melee-resolved-strike-id) fresh)))
      (let [next-tick (game/step at-expiry
                                 {:runner {:horizontal 1 :jump? true
                                           :drop? true :attack? true}}
                                 0.025)
            still-locked (get-in next-tick [:players :runner])]
        (is (true? (:spawn-locked? still-locked)))
        (is (== 320 (:x still-locked)))
        (is (zero? (:melee-strike-id still-locked)))))))

(deftest held-direction-starts-only-after-the-first-respawn-landing
  (let [waiting (assoc (game/make-player "#4ade80" 320 20)
                       :lifecycle :respawning :stocks 2 :respawn-seconds-left 0.025)
        returned (game/step (scene {:runner waiting})
                            {:runner {:horizontal 1 :jump? true :attack? true}} 0.025)
        landed (loop [world returned n 0]
                 (assert (< n 60) "Respawn should land within the bounded fixture")
                 (let [player (get-in world [:players :runner])]
                   (is (== 320 (:x player)))
                   (is (zero? (:melee-strike-id player)))
                   (if (:spawn-locked? player)
                     (recur (game/step world {:runner {:horizontal 1}} 0.025) (inc n))
                     world)))
        moving (game/step landed {:runner {:horizontal 1}} 0.025)]
    (is (> (get-in moving [:players :runner :x]) 320))
    (is (zero? (get-in moving [:players :runner :melee-strike-id])))))

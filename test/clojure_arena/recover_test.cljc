(ns clojure-arena.recover-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)
(def bounds {:left -1000 :right 2000 :top -1000 :bottom 1000})
(def floor {:id :floor :type :solid :x 0 :y 100 :width 1200 :height 16})

(defn- close-to? [actual expected]
  (< (abs (- actual expected)) 1e-9))

(defn- body [x & {:as overrides}]
  (merge (assoc (game/make-player "#22d3ee" x 84) :spawn-locked? false) overrides))

(defn- scene [players]
  {:blast-bounds bounds :platforms [floor] :players players})

(defn- run-ticks [world inputs]
  (reductions (fn [world input] (game/step world input dt)) world inputs))

(defn- after [world inputs id]
  (get-in (last (run-ticks world inputs)) [:players id]))

(defn- recover-once [percentage]
  (after (scene {:target (body 300 :accumulated-percentage percentage)})
         [{:target {:recover? true}}] :target))

(deftest e-reduces-percentage-by-thirty-percent-of-its-current-value
  (is (close-to? 56 (:accumulated-percentage (recover-once 80))))
  (is (close-to? 14 (:accumulated-percentage (recover-once 20))))
  (is (= game/recover-cooldown (:recover-cooldown-left (recover-once 80)))
      "the cooldown is not decremented on its activation tick")
  (let [rejected (recover-once 0)]
    (is (zero? (:accumulated-percentage rejected)))
    (is (zero? (:recover-cooldown-left rejected)) "E at exactly zero spends no cooldown")))

(deftest e-is-ready-again-after-exactly-480-ticks
  (let [worlds (vec (run-ticks (scene {:target (body 300 :accumulated-percentage 100)})
                               (repeat 482 {:target {:recover? true}})))
        activations (keep-indexed (fn [index world]
                                    (when (= game/recover-cooldown
                                             (get-in world [:players :target :recover-cooldown-left]))
                                      index))
                                  worlds)]
    (is (= [1 481] activations))))

(deftest e-changes-only-percentage-and-its-cooldown
  (let [moving (body 300 :accumulated-percentage 50 :vx 300 :knockback-vx 300 :stocks 2
                     :air-jumps-left 0 :protection-seconds-left 1)
        healed (after (scene {:target moving}) [{:target {:recover? true}}] :target)
        plain (after (scene {:target moving}) [{}] :target)]
    (is (close-to? 35 (:accumulated-percentage healed)))
    (is (= (dissoc plain :accumulated-percentage :recover-cooldown-left)
           (dissoc healed :accumulated-percentage :recover-cooldown-left))
        "no stock, velocity, received motion, air jump or protection change")))

(deftest a-same-tick-hit-scales-from-the-healed-percentage
  (let [duel (fn [percentage]
               (scene {:attacker (body 134 :facing :right)
                       :target (body 184 :facing :right :accumulated-percentage percentage)}))
        healed (after (duel 80) [{:attacker {:attack? true} :target {:recover? true}}] :target)
        reference (after (duel 56) [{:attacker {:attack? true}}] :target)]
    (is (close-to? 66 (:accumulated-percentage healed)) "E applies before the +10 hit")
    (is (close-to? (:knockback-vx reference) (:knockback-vx healed))
        "the impulse uses the healed final percentage")
    (is (close-to? (:knockback-vy reference) (:knockback-vy healed)))))

(deftest e-coexists-with-q-and-needs-an-unlocked-body
  (let [both (after (scene {:target (body 300 :accumulated-percentage 40)})
                    [{:target {:recover? true :antiknockback? true :attack? true}}] :target)
        locked (after (scene {:target (body 300 :accumulated-percentage 40 :spawn-locked? true)})
                      [{:target {:recover? true}}] :target)]
    (is (close-to? 28 (:accumulated-percentage both)))
    (is (game/antiknockback-active? both))
    (is (= 1 (:melee-strike-id both)))
    (is (= 40 (:accumulated-percentage locked)))
    (is (zero? (:recover-cooldown-left locked)))))

(deftest e-pressed-during-a-dash-applies-right-after-it
  (testing "a buffered E heals on tick 9"
    (let [worlds (vec (run-ticks (scene {:target (body 300 :accumulated-percentage 80)})
                                 (concat [{:target {:dash? true}} {:target {:recover? true}}]
                                         (repeat 7 {}))))
          target-at #(get-in worlds [% :players :target])]
      (is (every? #(:recover-buffered? (target-at %)) (range 2 9)))
      (is (every? #(= 80 (:accumulated-percentage (target-at %))) (range 1 9)))
      (is (close-to? 56 (:accumulated-percentage (target-at 9))))
      (is (false? (:recover-buffered? (target-at 9))))))
  (testing "a buffered E at zero percentage is discarded without cooldown"
    (let [target (after (scene {:target (body 300)})
                        (concat [{:target {:dash? true}} {:target {:recover? true}}] (repeat 7 {}))
                        :target)]
      (is (false? (:recover-buffered? target)))
      (is (zero? (:recover-cooldown-left target))))))

(deftest ring-out-clears-a-buffered-e
  (let [edge {:left -100 :right 330 :top -1000 :bottom 1000}
        world {:blast-bounds edge :platforms [floor]
               :players {:target (body 300 :accumulated-percentage 30)}}
        lost (->> (run-ticks world (concat [{:target {:dash? true}} {:target {:recover? true}}]
                                          (repeat 6 {})))
                  (filter #(= :respawning (get-in % [:players :target :lifecycle])))
                  first)]
    (is (false? (get-in lost [:players :target :recover-buffered?])))))

(deftest winner-comparison-uses-the-unrounded-percentage
  (let [contenders {:player-1 {:color "#22d3ee" :stocks 2 :forfeited? false
                               :accumulated-percentage (* 80 game/recover-factor)}
                    :player-2 {:color "#e11d48" :stocks 2 :forfeited? false
                               :accumulated-percentage 56.04}}
        result (match/result-for contenders 0)]
    (is (= :time-limit (:reason result)))
    (is (= :player-1 (:winner-id result)) "56 beats 56.04 although both display as 56 %")
    (is (nil? (:winner-id (match/result-for (assoc-in contenders [:player-2 :accumulated-percentage]
                                                      (* 80 game/recover-factor))
                                            0)))
        "only an exact stored tie draws")))

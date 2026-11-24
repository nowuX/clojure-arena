(ns clojure-arena.antiknockback-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)
(def bounds {:left -1000 :right 2000 :top -1000 :bottom 1000})
(def floor {:id :floor :type :solid :x 0 :y 100 :width 1200 :height 16})

(defn- close-to? [actual expected]
  (< (abs (- actual expected)) 1e-6))

(defn- body [x & {:as overrides}]
  (merge (assoc (game/make-player "#22d3ee" x 84) :spawn-locked? false) overrides))

(defn- scene [players]
  {:blast-bounds bounds :platforms [floor] :players players})

(defn- run-ticks [world inputs]
  (reductions (fn [world input] (game/step world input dt)) world inputs))

(defn- target-after [world inputs]
  (get-in (last (run-ticks world inputs)) [:players :target]))

(defn- duel
  "An attacker 50 px left of a target, both facing right and in melee reach."
  [& {:as target-overrides}]
  (scene {:attacker (body 134 :facing :right)
          :target (apply body 184 (mapcat identity target-overrides))}))

(deftest q-lasts-exactly-200-ticks-and-cools-down-for-400
  (let [worlds (vec (run-ticks (scene {:target (body 300)})
                               (repeat 402 {:target {:antiknockback? true}})))
        active (mapv #(game/antiknockback-active? (get-in % [:players :target])) worlds)
        activations (keep-indexed (fn [index world]
                                    (when (= game/antiknockback-duration
                                             (get-in world [:players :target :antiknockback-seconds-left]))
                                      index))
                                  worlds)]
    (is (= game/antiknockback-cooldown
           (get-in worlds [1 :players :target :antiknockback-cooldown-left]))
        "the cooldown is not decremented on its activation tick")
    (is (every? true? (subvec active 1 201)) "activation tick plus 199 later ticks")
    (is (false? (nth active 201)) "Q expires before hit evaluation on the 200th later tick")
    (is (= [1 401] activations)
        "held or repeated requests neither refresh Q nor bypass the 10 s cooldown")))

(deftest q-hits-add-percentage-without-a-new-impulse
  (let [strike {:attacker {:attack? true} :target {:antiknockback? true}}
        protected (target-after (duel) [strike])
        unprotected (target-after (duel) [{:attacker {:attack? true}}])
        untouched (target-after (duel) [{:target {:antiknockback? true}}])]
    (is (= 10 (:accumulated-percentage protected)))
    (is (close-to? (:vx protected) (:vx untouched)))
    (is (close-to? (:vy protected) (:vy untouched)))
    (is (zero? (:knockback-vx protected)))
    (is (pos? (:knockback-vx unprotected)) "the same strike launches a target without Q")))

(deftest q-keeps-received-motion-from-earlier-hits
  (let [launched (body 300 :vx 300 :knockback-vx 300)
        with-q (target-after (scene {:target launched}) [{:target {:antiknockback? true}}])
        without-q (target-after (scene {:target launched}) [{}])]
    (is (game/antiknockback-active? with-q))
    (is (close-to? (:knockback-vx with-q) (:knockback-vx without-q)))
    (is (close-to? (:x with-q) (:x without-q)))))

(deftest immunity-takes-precedence-over-q
  (testing "spawn protection blocks the percentage too, and Q does not end it"
    (let [target (target-after (duel :protection-seconds-left 2)
                               [{:attacker {:attack? true} :target {:antiknockback? true}}])]
      (is (zero? (:accumulated-percentage target)))
      (is (game/protected-player? target))
      (is (game/antiknockback-active? target))))
  (testing "Q and a dash may start together; dash immunity wins"
    (let [target (target-after (duel :facing :left)
                               [{:attacker {:attack? true}
                                 :target {:antiknockback? true :dash? true}}])]
      (is (game/dashing? target))
      (is (game/antiknockback-active? target))
      (is (zero? (:accumulated-percentage target))))))

(deftest q-coexists-with-an-attack-but-needs-an-unlocked-body
  (let [both (target-after (scene {:target (body 300)})
                           [{:target {:antiknockback? true :attack? true}}])
        locked (target-after (scene {:target (body 300 :spawn-locked? true)})
                             [{:target {:antiknockback? true}}])]
    (is (game/antiknockback-active? both))
    (is (= 1 (:melee-strike-id both)))
    (is (not (game/antiknockback-active? locked)))
    (is (zero? (:antiknockback-cooldown-left locked)) "a rejected request spends no cooldown")))

(deftest q-pressed-during-a-dash-activates-right-after-it
  (let [inputs (concat [{:target {:dash? true}}
                        {}
                        {:target {:antiknockback? true}}]
                       (repeat 5 {})
                       [{:attacker {:attack? true}}])
        wall {:id :wall :type :solid :x 200 :y 20 :width 16 :height 80}
        ;; The dash stops at the wall, keeping the target in the attacker's reach.
        worlds (vec (run-ticks (update (duel) :platforms conj wall) inputs))
        target-at #(get-in worlds [% :players :target])]
    (is (every? #(:antiknockback-buffered? (target-at %)) (range 3 9))
        "the request waits through the remaining dash ticks")
    (is (every? #(not (game/antiknockback-active? (target-at %))) (range 1 9)))
    (is (= game/antiknockback-duration (:antiknockback-seconds-left (target-at 9)))
        "Q starts on the first tick after the dash, before that tick's hits")
    (is (false? (:antiknockback-buffered? (target-at 9))))
    (is (= 10 (:accumulated-percentage (target-at 9))) "dash immunity has ended")
    (is (zero? (:knockback-vx (target-at 9))) "the tick 9 hit already has no impulse")))

(deftest a-buffered-q-respects-its-cooldown-and-ring-out-clears-it
  (testing "a cooling Q is discarded when the dash ends"
    (let [cooling (body 300 :antiknockback-cooldown-left 5)
          worlds (vec (run-ticks (scene {:target cooling})
                                 (concat [{:target {:dash? true}}
                                          {:target {:antiknockback? true}}]
                                         (repeat 7 {}))))
          after (get-in worlds [9 :players :target])]
      (is (:antiknockback-buffered? (get-in worlds [2 :players :target])))
      (is (not (game/antiknockback-active? after)))
      (is (false? (:antiknockback-buffered? after)))))
  (testing "ring-out clears an active Q and the respawned body starts ready"
    (let [edge {:left -100 :right 330 :top -1000 :bottom 1000}
          world {:blast-bounds edge :platforms [floor]
                 :players {:target (body 300 :facing :right)}}
          worlds (run-ticks world (concat [{:target {:antiknockback? true :dash? true}}]
                                          (repeat 140 {})))
          lost (get-in (first (filter #(= :respawning (get-in % [:players :target :lifecycle]))
                                      worlds))
                       [:players :target])
          returned (get-in (last worlds) [:players :target])]
      (is (zero? (:antiknockback-seconds-left lost)))
      (is (false? (:antiknockback-buffered? lost)))
      (is (= :active (:lifecycle returned)))
      (is (zero? (:antiknockback-cooldown-left returned))))))

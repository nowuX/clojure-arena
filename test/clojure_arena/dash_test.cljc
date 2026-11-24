(ns clojure-arena.dash-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def dt 0.025)
(def bounds {:left -1000 :right 2000 :top -1000 :bottom 1000})
(def floor {:id :floor :type :solid :x 0 :y 100 :width 1200 :height 16})
(def ledge {:id :ledge :type :semisolid :x 0 :y 100 :width 1200 :height 8})

(defn- close-to? [actual expected]
  (< (abs (- actual expected)) 1e-6))

(defn- scene [platforms players]
  {:blast-bounds bounds :platforms platforms :players players})

(defn- runner
  "An unlocked body standing on y = 100 surfaces."
  [x & {:as overrides}]
  (merge (assoc (game/make-player "#22d3ee" x 84) :spawn-locked? false)
         overrides))

(defn- run-ticks
  "Steps once per input map and returns every intermediate world."
  [world inputs]
  (reductions (fn [world input] (game/step world input dt)) world inputs))

(defn- dash-input [& {:as extra}]
  {:runner (merge {:dash? true} extra)})

(deftest dash-covers-80-px-in-eight-ticks-with-immunity-through-the-eighth
  (let [worlds (run-ticks (scene [floor] {:runner (runner 300)})
                          (cons (dash-input) (repeat 9 {})))
        players (mapv #(get-in % [:players :runner]) worlds)]
    (is (= 8 (:dash-ticks-left (nth players 1))) "the activation tick is dash tick 1 of 8")
    (is (= game/dash-cooldown (:dash-cooldown-left (nth players 1)))
        "the cooldown is not decremented on its activation tick")
    (is (every? game/dashing? (subvec players 1 9)) "ticks 1-8 are dash and immunity ticks")
    (is (every? game/immune-player? (subvec players 1 9)))
    (is (not (game/dashing? (nth players 9))) "immunity expires before tick 9 hit evaluation")
    (is (close-to? (:x (nth players 8)) 380) "8 ticks at 400 units/s cover exactly 80 px")
    (is (= 1 (:air-jumps-left (nth players 8))))
    (is (close-to? (:y (nth players 8)) 84) "a flat supporting top does not stop dash travel")))

(defn- target-percentage-after-strike-on
  "A dash into a wall keeps the target in reach; the attacker strikes on `strike-tick`."
  [strike-tick]
  (let [wall {:id :wall :type :solid :x 200 :y 20 :width 16 :height 80}
        world (scene [floor wall]
                     {:attacker (runner 134 :facing :right)
                      :runner (runner 184 :facing :right)})
        inputs (for [tick (range 1 (inc strike-tick))]
                 (cond-> {}
                   (= tick 1) (assoc :runner {:dash? true})
                   (= tick strike-tick) (assoc :attacker {:attack? true})))]
    (get-in (last (run-ticks world inputs)) [:players :runner :accumulated-percentage])))

(deftest hits-on-dash-ticks-1-to-8-miss-and-tick-9-lands
  (doseq [tick (range 1 9)]
    (is (zero? (target-percentage-after-strike-on tick))
        (str "a strike on dash tick " tick " is consumed without effect")))
  (is (= 10 (target-percentage-after-strike-on 9))))

(deftest a-solid-wall-stops-travel-but-keeps-lock-and-immunity
  (let [wall {:id :wall :type :solid :x 200 :y 20 :width 16 :height 80}
        inputs (concat [(dash-input)]
                       [{:runner {:jump? true :attack? true :horizontal -1}}]
                       (repeat 6 {}))
        worlds (run-ticks (scene [floor wall] {:runner (runner 184 :facing :right)}) inputs)
        players (mapv #(get-in % [:players :runner]) worlds)
        locked-tick (nth players 2)]
    (is (:dash-stopped? (nth players 1)))
    (is (close-to? (:x (nth players 1)) 184) "the opposing contact consumes this tick's travel")
    (is (game/dashing? locked-tick) "an early stop does not shorten immunity")
    (is (close-to? (:y locked-tick) 84) "jumps stay locked until the original deadline")
    (is (zero? (:melee-strike-id locked-tick)) "attacks stay locked during remaining immunity")
    (is (= :right (:facing locked-tick)) "steering cannot change the locked facing")
    (is (every? #(close-to? (:x %) 184) (subvec players 1 9))
        "later dash ticks keep zero voluntary X instead of restarting travel")))

(deftest a-fast-dash-cannot-tunnel-through-a-thin-wall
  (let [wall {:id :thin :type :solid :x 320 :y 20 :width 2 :height 80}
        worlds (run-ticks (scene [floor wall] {:runner (runner 300 :facing :right)})
                          (cons (dash-input) (repeat 8 {})))]
    (is (every? #(<= (get-in % [:players :runner :x]) (- 320 16 -1e-9)) worlds))))

(deftest air-dash-keeps-vertical-motion-and-the-air-jump
  (let [airborne (runner 300 :y -200 :vy 0)
        dashed (get-in (game/step (scene [floor] {:runner airborne}) (dash-input) dt)
                       [:players :runner])
        plain (get-in (game/step (scene [floor] {:runner airborne}) {} dt)
                      [:players :runner])]
    (is (game/dashing? dashed))
    (is (= 1 (:air-jumps-left dashed)))
    (is (close-to? (:vy dashed) (:vy plain)) "gravity and vertical velocity are unchanged")
    (is (close-to? (:y dashed) (:y plain)))
    (is (close-to? (- (:x dashed) 300) 10))))

(deftest locomotion-priority-is-drop-then-jump-then-dash
  (let [step-runner (fn [platforms player input]
                      (get-in (game/step (scene platforms {:runner player}) {:runner input} dt)
                              [:players :runner]))]
    (testing "an eligible drop or jump wins and spends no dash cooldown"
      (let [dropped (step-runner [ledge] (runner 300) {:drop? true :dash? true})
            jumped (step-runner [floor] (runner 300) {:jump? true :dash? true})]
        (is (= :ledge (:drop-through-platform-id dropped)))
        (is (neg? (:vy jumped)))
        (doseq [player [dropped jumped]]
          (is (not (game/dashing? player)))
          (is (zero? (:dash-cooldown-left player))))))
    (testing "a rejected drop or jump does not block an eligible dash"
      (let [solid-drop (step-runner [floor] (runner 300) {:drop? true :dash? true})
            spent-jump (step-runner [floor] (runner 300 :y -200 :air-jumps-left 0)
                                    {:jump? true :dash? true})]
        (is (game/dashing? solid-drop) "S on a solid platform is not an eligible drop")
        (is (game/dashing? spent-jump) "W without an air jump is not an eligible jump")))))

(deftest dash-requests-need-an-unlocked-body-and-a-ready-cooldown
  (let [world (scene [floor] {:runner (runner 300)})
        activations (->> (run-ticks world (repeat 102 (dash-input)))
                         (map-indexed (fn [index world]
                                        [index (get-in world [:players :runner :dash-ticks-left])]))
                         (keep (fn [[index ticks-left]] (when (= 8 ticks-left) index))))]
    (is (= [1 101] activations) "the 2.5 s cooldown spans exactly 100 ticks")
    (is (not (game/dashing? (get-in (game/step (scene [floor] {:runner (runner 300 :spawn-locked? true)})
                                               (dash-input) dt)
                                    [:players :runner])))
        "spawn-locked bodies cannot dash")
    (is (zero? (:melee-strike-id (get-in (game/step world (dash-input :attack? true) dt)
                                         [:players :runner])))
        "an attack on the activation tick is rejected")))

(deftest the-tick-after-a-dash-clamps-only-voluntary-speed
  (let [pushed (runner 300 :vx 300 :knockback-vx 300)
        worlds (run-ticks (scene [floor] {:runner pushed}) (cons (dash-input) (repeat 8 {})))
        after (get-in (last worlds) [:players :runner])
        friction-step (* game/horizontal-friction dt)
        expected-knockback (- 300 (* 9 friction-step))]
    (is (close-to? (:knockback-vx after) expected-knockback)
        "received X decays normally through the whole dash")
    (is (close-to? (- (:vx after) (:knockback-vx after)) (- game/horizontal-speed friction-step))
        "voluntary X is clamped to 220 before ordinary friction on tick 9")))

(deftest ring-out-clears-an-active-dash
  (let [small-bounds {:left -100 :right 330 :top -1000 :bottom 1000}
        world {:blast-bounds small-bounds :platforms [floor] :players {:runner (runner 300)}}
        worlds (run-ticks world (cons (dash-input) (repeat 7 {})))
        lost (first (filter #(= :respawning (get-in % [:players :runner :lifecycle])) worlds))
        player (get-in lost [:players :runner])]
    (is (some? lost))
    (is (zero? (:dash-ticks-left player)))
    (is (false? (:dash-stopped? player)))))

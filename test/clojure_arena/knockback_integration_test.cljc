(ns clojure-arena.knockback-integration-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(defn- test-player [x y attributes]
  (merge (assoc (game/make-player "#ffffff" x y) :spawn-locked? false)
         attributes))

(defn- world [players platforms]
  {:players players :platforms platforms})

(defn- player [state id]
  (get-in state [:players id]))

(defn- close-to? [actual expected]
  #?(:clj (<= (Math/abs (- actual expected)) 1e-8)
     :cljs (<= (js/Math.abs (- actual expected)) 1e-8)))

(def floor
  {:id :floor :type :solid :x 0 :y 100 :width 400 :height 8})

(deftest accepted-strikes-add-retained-and-total-velocity-after-movement
  (let [fresh (game/make-player "#ffffff" 10 20)
        initial (world {:attacker (test-player 100 100 {:facing :right})
                        :target (test-player 120 100 {})}
                       [])
        hit (game/step initial {:attacker {:attack? true}} 0)
        attacker (player hit :attacker)
        target (player hit :target)]
    (is (close-to? (:knockback-vx fresh) 0))
    (is (close-to? (:knockback-vy fresh) 0))
    (is (= 1 (:melee-strike-id attacker)))
    (is (= 1 (:melee-resolved-strike-id attacker)))
    (is (= #{:target} (:melee-target-ids attacker)))
    (is (= 10 (:accumulated-percentage target)))
    (is (close-to? (:vx target) 184))
    (is (close-to? (:vy target) -138))
    (is (close-to? (:knockback-vx target) 184))
    (is (close-to? (:knockback-vy target) -138))
    (is (= [120 100] ((juxt :x :y) target))
        "the new impulse does not move the target until the next tick")))

(deftest target-selection-uses-the-position-after-movement
  (let [initial (world {:attacker (test-player 100 100 {:facing :right})
                        :target (test-player 168 100 {:vx -220})}
                       [])
        hit (game/step initial {:attacker {:attack? true}} 0.05)
        target (player hit :target)]
    (is (close-to? (:x target) 159.75))
    (is (= #{:target} (:melee-target-ids (player hit :attacker))))
    (is (= 10 (:accumulated-percentage target)))
    (is (close-to? (:knockback-vx target) 184))
    (is (close-to? (:vx target) 19))
    (is (close-to? (:vy target) -89))))

(deftest simultaneous-hits-use-final-percentage-and-ignore-map-order
  (let [entries [[:right-high (test-player 155 70 {:facing :right})]
                 [:right-low (test-player 155 130 {:facing :right})]
                 [:left (test-player 245 100 {:facing :left})]
                 [:target (test-player 200 100
                                       {:vx 40 :knockback-vx 25
                                        :accumulated-percentage 20})]]
        inputs (into (array-map)
                     (map (fn [[id _]] [id {:attack? true}])
                          (butlast entries)))
        make-world (fn [player-entries]
                     (world (into (array-map) player-entries)
                            [(assoc floor :x 0 :y 116 :width 400)]))
        forward (game/step (make-world entries) inputs 0)
        reversed (game/step (make-world (reverse entries))
                            (into (array-map) (reverse inputs))
                            0)
        target (player forward :target)]
    (is (= (:players forward) (:players reversed)))
    (is (= 50 (:accumulated-percentage target)))
    (is (close-to? (:vx target) 320))
    (is (close-to? (:knockback-vx target) 305))
    (is (close-to? (:vy target) -630))
    (is (close-to? (:knockback-vy target) -630))
    (is (= [200 100] ((juxt :x :y) target)))))

(deftest following-tick-brakes-received-x-without-the-voluntary-speed-cap
  (let [initial (world {:attacker (test-player 100 100 {:facing :right})
                        :target (test-player 120 100
                                             {:accumulated-percentage 100})}
                       [])
        hit (game/step initial {:attacker {:attack? true}} 0)]
    (is (close-to? (:knockback-vx (player hit :target)) 424))
    (doseq [[horizontal expected-total]
            [[0 396.5]
             [1 418.5]
             [-1 374.5]]]
      (testing (str "horizontal intention " horizontal)
        (let [next-world (game/step hit {:target {:horizontal horizontal}} 0.025)
              target (player next-world :target)]
          (is (close-to? (:knockback-vx target) 396.5))
          (is (close-to? (- (:knockback-vx target)
                            (:knockback-vx (player hit :target)))
                         -27.5)
              "received X decays by 1100 units/s² over 25 ms")
          (is (close-to? (:vx target) expected-total))
          (is (> (:vx target) 220)
              "received motion remains outside the voluntary speed cap"))))))

(deftest wall-contact-projects-total-and-retained-motion
  (let [wall {:id :wall :type :solid :x 130 :y 0 :width 16 :height 200}
        initial (world {:attacker (test-player 90 100 {:facing :right})
                        :target (test-player 110 100 {})}
                       [wall])
        hit (game/step initial {:attacker {:attack? true}} 0)
        contact (game/step hit {} 0.1)
        after-contact (game/step contact {} 0.025)
        target (player contact :target)]
    (is (close-to? (:x target) 114))
    (is (close-to? (:vx target) 0))
    (is (close-to? (:knockback-vx target) 0))
    (is (close-to? (:knockback-vy target) -138))
    (is (close-to? (:x (player after-contact :target)) 114))
    (is (close-to? (:knockback-vx (player after-contact :target)) 0))))

(deftest rising-knockback-traverses-a-semisolid-then-lands-and-can-drop
  (let [semisolid {:id :upper :type :semisolid
                   :x 0 :y 100 :width 500 :height 8}
        initial (world {:attacker (test-player 130 120 {:facing :right})
                        :target (test-player 150 120
                                             {:accumulated-percentage 100})}
                       [semisolid])
        hit (game/step initial {:attacker {:attack? true}} 0)
        trajectory (take 100 (iterate #(game/step % {} 0.025) hit))
        rose-through (first (filter #(let [target (player % :target)]
                                       (and (< (:y target) 84)
                                            (neg? (:vy target))))
                                    trajectory))
        landed (first (filter #(let [target (player % :target)]
                                 (and (close-to? (:y target) 84)
                                      (zero? (:vy target))))
                              trajectory))]
    (is (close-to? (:knockback-vy (player hit :target)) -318))
    (is (some? rose-through) "the rising impulse passes through the one-way platform")
    (is (some? landed) "the same trajectory later lands on the platform top")
    (when landed
      (let [target (player landed :target)
            dropped (game/step landed {:target {:drop? true}} 0.025)
            dropped-target (player dropped :target)]
        (is (close-to? (:y target) 84))
        (is (close-to? (:vy target) 0))
        (is (close-to? (:knockback-vy target) 0))
        (is (= 1 (:air-jumps-left target)))
        (is (= :upper (:drop-through-platform-id dropped-target)))
        (is (> (:y dropped-target) 84))
        (is (pos? (:vy dropped-target)))))))

(deftest valid-ground-and-air-jumps-replace-total-y-and-clear-retained-y
  (let [ground-world (world
                       {:player (test-player 100 84
                                             {:vx 130 :vy 0
                                              :knockback-vx 130 :knockback-vy 60
                                              :air-jumps-left 1})}
                       [floor])
        ground-jump (game/step ground-world {:player {:jump? true}} 0.025)
        ground-target (player ground-jump :player)
        air-world (world
                    {:player (test-player 200 200
                                          {:vx 130 :vy -100
                                           :knockback-vx 130 :knockback-vy -200
                                           :air-jumps-left 1})}
                    [])
        air-jump (game/step air-world {:player {:jump? true}} 0.025)
        air-target (player air-jump :player)]
    (testing "a supported jump preserves the air jump and retained X"
      (is (close-to? (:vy ground-target) -395.5)
          "-420 launch velocity receives gravity after the jump is accepted")
      (is (close-to? (:knockback-vy ground-target) 0))
      (is (close-to? (:knockback-vx ground-target) 102.5))
      (is (close-to? (:vx ground-target) 102.5))
      (is (= 1 (:air-jumps-left ground-target))))
    (testing "an air jump spends its allowance and keeps damped received X"
      (is (close-to? (:vy air-target) -395.5)
          "the air launch also replaces total Y before gravity")
      (is (close-to? (:knockback-vy air-target) 0))
      (is (close-to? (:knockback-vx air-target) 102.5))
      (is (close-to? (:vx air-target) 102.5))
      (is (= 0 (:air-jumps-left air-target))))))

(deftest simultaneous-mutual-strikes-resolve-once-in-either-player-order
  (let [entries [[:left (test-player 100 100 {:facing :right})]
                 [:right (test-player 140 100 {:facing :left})]]
        inputs {:left {:attack? true} :right {:attack? true}}
        run (fn [player-entries player-inputs]
              (game/step (world (into (array-map) player-entries) [])
                         (into (array-map) player-inputs)
                         0))
        forward (run entries inputs)
        reversed (run (reverse entries) (reverse inputs))
        players (:players forward)]
    (is (= players (:players reversed)))
    (is (= players (game/apply-melee-hits players))
        "reapplying consumed strikes does not add percentage or impulses again")
    (doseq [[id other-id expected-x]
            [[:left :right -184]
             [:right :left 184]]]
      (let [struck (player forward id)]
        (is (= 1 (:melee-strike-id struck)))
        (is (= 1 (:melee-resolved-strike-id struck)))
        (is (= #{other-id} (:melee-target-ids struck)))
        (is (= 10 (:accumulated-percentage struck)))
        (is (close-to? (:knockback-vx struck) expected-x))
        (is (close-to? (:knockback-vy struck) -138))
        (is (close-to? (:vx struck) expected-x))
        (is (close-to? (:vy struck) -138))))))

(deftest received-and-voluntary-x-brake-independently-after-a-real-hit
  (let [initial (world {:attacker (test-player 100 100 {:facing :right})
                        :target (test-player 140 100 {:vx 220})} [])
        hit (game/step initial {:attacker {:attack? true}} 0)]
    (doseq [[horizontal expected-voluntary] [[0 192.5] [1 220] [-1 198]]]
      (let [after (player (game/step hit {:target {:horizontal horizontal}} 0.025) :target)]
        (is (close-to? (:knockback-vx after) 156.5))
        (is (close-to? (- (:vx after) (:knockback-vx after)) expected-voluntary))))))

(deftest a-real-hit-into-a-rounded-corner-projects-both-velocity-vectors
  (let [block {:id :block :type :solid :x 150 :y 100 :width 80 :height 16}
        initial (world {:attacker (test-player 95 145 {:facing :right})
                        :target (test-player 115 145 {:accumulated-percentage 100
                                                     :air-jumps-left 0})} [block])
        hit (game/step initial {:attacker {:attack? true}} 0)
        resolve-contact game/resolve-contact
        contacts (atom [])]
    (with-redefs [game/resolve-contact
                  (fn [before contact]
                    (let [after (resolve-contact before contact)]
                      (swap! contacts conj [before contact after])
                      after))]
      (game/step hit {} 0.1))
    (let [[_ contact after]
          (first (filter (fn [[_ {:keys [nx ny]} _]]
                           (and (not (zero? nx)) (not (zero? ny)))) @contacts))]
      (is (some? contact) "the received launch reaches a rounded corner")
      (when contact
        (let [{:keys [nx ny]} contact]
          (is (close-to? (+ (* nx (:vx after)) (* ny (:vy after))) 0))
          (is (close-to? (+ (* nx (:knockback-vx after))
                            (* ny (:knockback-vy after))) 0))
          (is (= 0 (:air-jumps-left after)))
          (is (= :right (:facing after))))))))

(deftest a-rejected-air-jump-does-not-erase-received-vertical-motion
  (let [initial (world {:attacker (test-player 100 100 {:facing :right})
                        :target (test-player 140 100 {:air-jumps-left 0})} [])
        hit (game/step initial {:attacker {:attack? true}} 0)
        after (player (game/step hit {:target {:jump? true}} 0.025) :target)]
    (is (close-to? (:vy after) -113.5))
    (is (close-to? (:knockback-vy after) -138))
    (is (= 0 (:air-jumps-left after)))))

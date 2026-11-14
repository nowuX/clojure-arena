(ns clojure-arena.ring-out-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- world-with [players]
  (assoc game/initial-world :platforms [] :players players))

(deftest ring-outs-consume-once-and-freeze-inactive-players
  (let [player (assoc (game/make-player "#22d3ee" 400 160)
                      :x 900 :vx 300 :vy 100 :knockback-vx 200 :knockback-vy 50
                      :spawn-locked? false :accumulated-percentage 80
                      :melee-strike-id 3 :melee-resolved-strike-id 2
                      :melee-target-ids #{:player-2})
        first-loss (game/step (world-with {:player-1 player}) {} 0)
        lost (get-in first-loss [:players :player-1])
        inactive (game/step first-loss {:player-1 {:horizontal 1 :jump? true :drop? true :attack? true}} 0.025)]
    (is (= [2 :respawning 80] ((juxt :stocks :lifecycle :accumulated-percentage) lost)))
    (is (every? zero? ((juxt :vx :vy :knockback-vx :knockback-vy) lost)))
    (is (= #{} (:melee-target-ids lost)))
    (is (= 3 (:melee-resolved-strike-id lost)))
    (is (= (dissoc lost :respawn-seconds-left)
           (dissoc (get-in inactive [:players :player-1]) :respawn-seconds-left)))
    (is (= lost (game/step-player lost [] {:horizontal 1 :jump? true :attack? true} 0.025)))
    (let [second-life (assoc lost :lifecycle :active)
          second-loss (game/resolve-ring-out second-life (:blast-bounds first-loss))
          third-loss (game/resolve-ring-out (assoc second-loss :lifecycle :active)
                                            (:blast-bounds first-loss))]
      (is (= [1 :respawning] ((juxt :stocks :lifecycle) second-loss)))
      (is (= [0 :eliminated] ((juxt :stocks :lifecycle) third-loss)))
      (is (= third-loss (game/resolve-ring-out third-loss (:blast-bounds first-loss)))))))

(deftest exits-resolve-before-melee-and-do-not-charge-other-players
  (let [a (assoc (game/make-player "#22d3ee" 900 160) :spawn-locked? false :facing :left)
        b (assoc (game/make-player "#e11d48" 860 160) :spawn-locked? false)
        before (world-with {:player-1 a :player-2 b})
        after (game/step before {:player-1 {:attack? true} :player-2 {:attack? true}} 0)]
    (is (= 2 (get-in after [:players :player-1 :stocks])))
    (is (= b (assoc (get-in after [:players :player-2])
                    :melee-cooldown-left 0 :melee-strike-id 0 :melee-resolved-strike-id 0)))
    (is (= 0 (get-in after [:players :player-1 :accumulated-percentage])))
    (is (= 0 (get-in after [:players :player-2 :accumulated-percentage])))
    (is (= #{} (get-in after [:players :player-2 :melee-target-ids])))))

(deftest simultaneous-exits-are-independent
  (let [before (world-with {:player-1 (game/make-player "#22d3ee" -97 160)
                            :player-2 (assoc (game/make-player "#e11d48" 897 160) :stocks 1)})
        after (game/step before {} 0)]
    (is (= [2 0] (mapv :stocks (vals (:players after)))))
    (is (= [:respawning :eliminated] (mapv :lifecycle (vals (:players after)))))
    (is (= before (world-with (:players before))))))

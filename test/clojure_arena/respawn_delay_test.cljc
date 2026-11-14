(ns clojure-arena.respawn-delay-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- tick [world]
  (game/step world {:player-1 {:horizontal 1 :jump? true :attack? true}} 0.025))

(deftest respawn-delay-starts-full-and-reaches-zero-on-the-120th-later-tick
  (let [before (assoc game/initial-world :players
                      {:player-1 (game/make-player "#22d3ee" 400 160)})
        loss (tick (assoc-in before [:players :player-1 :x] 900))
        at119 (nth (iterate tick loss) 119)
        at120 (tick at119)
        player (get-in at120 [:players :player-1])]
    (is (= 3 (get-in loss [:players :player-1 :respawn-seconds-left])))
    (is (< 0 (get-in at119 [:players :player-1 :respawn-seconds-left]) 0.026))
    (is (= 0 (:respawn-seconds-left player)))
    (is (= :respawning (:lifecycle player)))
    (is (= 2 (:stocks player)))
    (is (= (get-in loss [:players :player-1 :x]) (:x player)))
    (is (= at120 (tick at120)))
    (is (protocol/snapshot-message? {:type :snapshot :tick 120 :world at120}))))

(deftest timer-progress-is-independent-and-zero-stock-records-never-count-down
  (let [a (assoc (game/make-player "#22d3ee" 400 160) :lifecycle :respawning
                 :stocks 2 :respawn-seconds-left 3)
        b (assoc (game/make-player "#e11d48" 440 160) :lifecycle :respawning
                 :stocks 1 :respawn-seconds-left 1)
        c (assoc (game/make-player "#f59e0b" 480 160) :lifecycle :eliminated :stocks 0)
        after (tick (assoc game/initial-world :players {:player-1 a :player-2 b :player-3 c}))]
    (is (= [2.975 0.975 0] (mapv :respawn-seconds-left (vals (:players after)))))
    (is (= c (get-in after [:players :player-3])))))

(deftest snapshots-reject-invalid-or-state-inconsistent-respawn-times
  (let [snapshot {:type :snapshot :tick 0 :world game/initial-world}
        path [:world :players :player-1 :respawn-seconds-left]]
    (doseq [timer [-1 3.01 ##Inf ##NaN nil "3"]]
      (is (not (protocol/snapshot-message? (assoc-in snapshot path timer)))))
    (is (not (protocol/snapshot-message? (assoc-in snapshot path 1))))))

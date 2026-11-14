(ns clojure-arena.match-victory-test
  (:require [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest respawning-survivor-wins-and-result-is-frozen-after-departure
  (let [running (-> (match/step (countdown/entrants) {} 3)
                    (update-in [:players :player-1] assoc :stocks 2 :lifecycle :respawning
                               :respawn-seconds-left 3 :protection-seconds-left 0)
                    (update-in [:players :player-2] assoc :stocks 0 :lifecycle :eliminated
                               :protection-seconds-left 0))
        finished (match/step running {} 0.025)
        result (get-in finished [:match :result])
        later (match/step finished {:player-1 {:attack? true :jump? true}} 0.025)
        departed (match/remove-player finished :player-1)]
    (is (= :finished (get-in finished [:match :phase])))
    (is (= :last-contender (:reason result)))
    (is (= :player-1 (:winner-id result)))
    (is (= (:players finished) (:players later)))
    (is (= (get-in finished [:match :time-seconds-left]) (get-in later [:match :time-seconds-left])))
    (is (= result (get-in departed [:match :result])))
    (is (protocol/snapshot-message? {:type :snapshot :tick 121 :world departed}))))

(deftest simultaneous-final-exits-draw-and-leave-resolves-a-single-survivor
  (let [running (match/step (countdown/entrants) {} 3)
        outside (update running :players
                        (fn [players] (into {} (map (fn [[id player]]
                                                    [id (assoc player :stocks 1 :x 900)])) players)))
        draw (match/step outside {} 0)
        left (match/remove-player running :player-2)]
    (is (= :no-contenders (get-in draw [:match :result :reason])))
    (is (nil? (get-in draw [:match :result :winner-id])))
    (is (= [0 0] (mapv :stocks (vals (:players draw)))))
    (is (= :player-1 (get-in left [:match :result :winner-id])))
    (is (= :last-contender (get-in left [:match :result :reason])))))

(ns clojure-arena.match-forfeit-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest departure-captures-latest-score-for-every-contender-lifecycle
  (doseq [[state stocks respawn] [[:active 2 0] [:respawning 1 2] [:eliminated 0 0]]]
    (let [running (-> (match/step (countdown/entrants) {} 3)
                      (update-in [:players :player-1] assoc :lifecycle state :stocks stocks
                                 :accumulated-percentage 70 :respawn-seconds-left respawn
                                 :protection-seconds-left 0))
          left (match/remove-player running :player-1)]
      (is (not (contains? (:players left) :player-1)))
      (is (= {:color "#22d3ee" :stocks stocks :accumulated-percentage 70 :forfeited? true}
             (get-in left [:match :contenders :player-1])))
      (is (= left (match/remove-player left :player-1)))
      (is (not (match/contender? left :player-1)))
      (is (protocol/snapshot-message? {:type :snapshot :tick 121 :world left})))))

(deftest observer-leave-and-color-reuse-do-not-alter-the-retired-contender
  (let [running (match/step (countdown/entrants) {} 3)
        observer (match/add-player running :player-3 (game/make-player "#4ade80" 460 160))
        departed (match/remove-player observer :player-1)
        replacement (match/add-player departed :player-4 (game/make-player "#22d3ee" 340 160))
        left (match/remove-player replacement :player-3)]
    (is (= (:match replacement) (:match left)))
    (is (true? (get-in left [:match :contenders :player-1 :forfeited?])))
    (is (not (contains? (get-in left [:match :contenders]) :player-4)))
    (is (= :waiting (get-in left [:players :player-4 :lifecycle])))
    (is (protocol/snapshot-message? {:type :snapshot :tick 122 :world left}))))

(ns clojure-arena.match-protocol-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest wire-rejects-inconsistent-phase-clock-and-roster-data
  (let [running (match/step (countdown/entrants) {} 3)
        snapshot {:type :snapshot :tick 120 :world running}]
    (is (protocol/snapshot-message? snapshot))
    (doseq [[path value] [[[:match :id] 0]
                          [[:match :id] 9007199254740992]
                          [[:match :time-seconds-left] 0]
                          [[:match :time-seconds-left] 181]
                          [[:match :countdown-seconds-left] 1]
                          [[:match :intermission-seconds-left] 1]
                          [[:match :contenders :player-1 :stocks] 1]
                          [[:match :contenders :player-1 :accumulated-percentage] 10]
                          [[:match :contenders :player-1 :forfeited?] true]
                          [[:players :player-3] (game/make-player "#4ade80" 460 160)]]]
      (is (not (protocol/snapshot-message? (assoc-in snapshot (into [:world] path) value)))))
    (doseq [timer [nil "3" ##NaN ##Inf -0.025]]
      (is (not (protocol/snapshot-message?
                 (assoc-in snapshot [:world :match :time-seconds-left] timer)))))))

(deftest wire-validates-captured-results-and-allows-a-departed-winner
  (let [running (match/step (countdown/entrants) {} 3)
        won (match/remove-player running :player-2)
        departed (match/remove-player won :player-1)
        snapshot {:type :snapshot :tick 121 :world departed}]
    (is (protocol/snapshot-message? snapshot))
    (is (= snapshot (protocol/decode (pr-str snapshot))))
    (doseq [invalid [(assoc-in snapshot [:world :match :result :winner-id] :player-2)
                     (assoc-in snapshot [:world :match :result :winner-color] "#4ade80")
                     (assoc-in snapshot [:world :match :result :reason] :time-limit)
                     (assoc-in snapshot [:world :match :result :standings 0 :stocks] 0)
                     (update-in snapshot [:world :match :result :standings] #(vec (reverse %)))
                     (assoc-in snapshot [:world :match :result :client-command] :reset)
                     (assoc-in snapshot [:world :match :intermission-seconds-left] 0)]]
      (is (not (protocol/snapshot-message? invalid))))))

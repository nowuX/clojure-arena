(ns clojure-arena.match-ranking-test
  (:require [clojure-arena.match-countdown-test :as countdown]
            [clojure-arena.match :as match]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(defn- contender [color stocks percentage forfeited?]
  {:color color
   :stocks stocks
   :accumulated-percentage percentage
   :forfeited? forfeited?})

(deftest time-limit-ranks-stocks-before-percentage-and-forfeits-last-test
  (let [result (match/result-for
                (array-map :player-2 (contender "#e11d48" 2 0 false)
                           :player-3 (contender "#f59e0b" 0 0 true)
                           :player-1 (contender "#22d3ee" 3 95 false))
                0)]
    (is (= :time-limit (:reason result)))
    (is (= :player-1 (:winner-id result))
        "more stocks outrank a much lower percentage")
    (is (= "#22d3ee" (:winner-color result)))
    (is (= [:player-1 :player-2 :player-3]
           (mapv :player-id (:standings result)))
        "forfeited contenders remain visible after eligible standings")))

(deftest lower-percentage-breaks-equal-stocks-but-an-exact-best-tie-draws-test
  (let [percentage-winner
        (match/result-for
         {:player-1 (contender "#22d3ee" 2 40 false)
          :player-2 (contender "#e11d48" 2 12 false)}
         0)
        forward (array-map :player-1 (contender "#22d3ee" 2 12 false)
                           :player-2 (contender "#e11d48" 2 12 false))
        reverse (array-map :player-2 (contender "#e11d48" 2 12 false)
                           :player-1 (contender "#22d3ee" 2 12 false))
        tied (match/result-for forward 0)
        tied-reverse (match/result-for reverse 0)]
    (is (= :player-2 (:winner-id percentage-winner)))
    (is (nil? (:winner-id tied))
        "stable player ID ordering is for display and cannot break a score tie")
    (is (= [:player-1 :player-2] (mapv :player-id (:standings tied))))
    (is (= tied tied-reverse)
        "collection insertion order does not change a tied result")))


(deftest the-expiry-tick-completes-hits-and-gives-last-contender-priority
  (let [started (match/step (countdown/entrants) {} 3)
        at-expiry (-> started
                      (assoc-in [:match :time-seconds-left] 0.025)
                      (update-in [:players :player-1] assoc :x 350 :y 264
                                 :spawn-locked? false :protection-seconds-left 0
                                 :stocks 2 :accumulated-percentage 5)
                      (update-in [:players :player-2] assoc :x 380 :y 264
                                 :spawn-locked? false :protection-seconds-left 0
                                 :stocks 2 :accumulated-percentage 0))
        finished (match/step at-expiry {:player-1 {:attack? true}} 0.025)
        departing (-> at-expiry
                      (update-in [:players :player-2] assoc :stocks 1 :x 900))
        last-one (match/step departing {} 0.025)]
    (is (= 10 (get-in finished [:match :contenders :player-2 :accumulated-percentage])))
    (is (= :time-limit (get-in finished [:match :result :reason])))
    (is (= :player-1 (get-in finished [:match :result :winner-id])))
    (is (= :last-contender (get-in last-one [:match :result :reason])))
    (is (= :player-1 (get-in last-one [:match :result :winner-id])))))

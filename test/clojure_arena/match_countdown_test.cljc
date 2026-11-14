(ns clojure-arena.match-countdown-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn entrants []
  (reduce (fn [world [id color x]]
            (match/add-player world id (game/make-player color x 160)))
          (assoc game/initial-world :players {} :match match/initial-match)
          [[:player-1 "#22d3ee" 340] [:player-2 "#e11d48" 380]]))

(deftest countdown-boundary-rebuilds-entrants-without-stepping-the-new-bodies
  (let [waiting (-> (entrants)
                    (assoc-in [:players :player-1 :accumulated-percentage] 70)
                    (assoc-in [:players :player-1 :vx] 200))
        before (nth (iterate #(match/step % {} 0.025) waiting) 119)
        started (match/step before {:player-1 {:attack? true :jump? true}} 0.025)]
    (is (= 3 (get-in waiting [:match :countdown-seconds-left])))
    (is (= :countdown (get-in before [:match :phase])))
    (is (= (:players waiting) (:players before)))
    (is (= :running (get-in started [:match :phase])))
    (is (= 1 (get-in started [:match :id])))
    (is (= #{:player-1 :player-2} (set (keys (get-in started [:match :contenders])))))
    (is (= (game/grant-spawn-protection (game/make-player "#22d3ee" 340 160))
           (get-in started [:players :player-1])))
    (is (protocol/snapshot-message? {:type :snapshot :tick 120 :world started}))))

(deftest cancellation-and-new-admission-get-a-full-new-countdown
  (let [partial (match/step (entrants) {} 2)
        cancelled (match/remove-player partial :player-2)
        held (match/step cancelled {} 20)
        restarted (match/add-player held :player-3 (game/make-player "#4ade80" 460 160))]
    (is (= :waiting (get-in cancelled [:match :phase])))
    (is (= cancelled held))
    (is (= :countdown (get-in restarted [:match :phase])))
    (is (= 3 (get-in restarted [:match :countdown-seconds-left])))
    (is (zero? (get-in restarted [:match :id])))))

(ns clojure-arena.match-observer-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest late-arrival-stays-visible-in-ownership-but-outside-the-match
  (let [started (match/step (countdown/entrants) {} 3)
        joined (match/add-player started :player-3 (game/make-player "#4ade80" 460 160))
        observer (get-in joined [:players :player-3])
        advanced (match/step joined {:player-3 {:horizontal -1 :attack? true :jump? true}} 0.025)]
    (is (= (:match started) (:match joined)))
    (is (= [0 :waiting] ((juxt :stocks :lifecycle) observer)))
    (is (not (match/playing? joined :player-3)))
    (is (= observer (get-in advanced [:players :player-3])))
    (is (protocol/snapshot-message? {:type :snapshot :tick 121 :world advanced}))))

(deftest only-roster-players-enter-physics-even-with-a-faulty-active-observer
  (let [started (match/step (countdown/entrants) {} 3)
        outsider (assoc (game/make-player "#4ade80" 350 264) :spawn-locked? false)
        world (assoc-in started [:players :player-3] outsider)
        next-world (match/step world {:player-3 {:attack? true :jump? true}} 0.025)]
    (is (= outsider (get-in next-world [:players :player-3])))
    (is (not (match/playing? world :player-3)))
    (is (every? zero? (map :accumulated-percentage (vals (:players next-world)))))))

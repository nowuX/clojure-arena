(ns clojure-arena.lifecycle-state-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest stocks-are-independent-from-percentage-and-other-players
  (let [a (game/make-player "#22d3ee" 400 160)
        b (game/make-player "#e11d48" 440 160)
        changed (assoc a :stocks 1 :accumulated-percentage 150)]
    (is (= [3 :active 0] ((juxt :stocks :lifecycle :accumulated-percentage) a)))
    (is (= 3 (:stocks b)))
    (is (= 150 (:accumulated-percentage changed)))
    (is (= 1 (:stocks changed)))
    (is (= 0 (:accumulated-percentage a)))))

(deftest snapshots-validate-lifecycle-and-stock-count-together
  (let [snapshot {:type :snapshot :tick 0 :world game/initial-world}
        path [:world :players :player-1]]
    (doseq [[lifecycle stocks] [[:active 3] [:active 1] [:respawning 2] [:eliminated 0]]]
      (let [message (update-in snapshot path assoc :lifecycle lifecycle :stocks stocks)]
        (is (protocol/snapshot-message? message))
        (is (= message (protocol/decode (pr-str message))))))
    (doseq [stocks [-1 4 1.5 nil "3" ##Inf ##NaN]]
      (is (not (protocol/snapshot-message? (assoc-in snapshot (conj path :stocks) stocks)))))
    (doseq [[lifecycle stocks] [[:waiting 0] [:unknown 3] [nil 3]
                                [:active 0] [:respawning 0] [:eliminated 1]]]
      (is (not (protocol/snapshot-message?
                 (update-in snapshot path assoc :lifecycle lifecycle :stocks stocks)))))))

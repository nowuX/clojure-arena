(ns clojure-arena.lifecycle-state-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- valid-snapshot? [snapshot]
  (protocol/snapshot-message? (update snapshot :world match-helpers/running-world)))

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
  (let [snapshot {:type :snapshot :tick 0 :world (match-helpers/running-world game/initial-world)}
        path [:world :players :player-1]]
    (doseq [[lifecycle stocks] [[:active 3] [:active 1] [:respawning 2] [:eliminated 0] [:waiting 0]]]
      (let [message (update-in snapshot path assoc :lifecycle lifecycle :stocks stocks
                                      :respawn-seconds-left (if (= lifecycle :respawning) 3 0))]
        (is (valid-snapshot? message))
        (is (= message (protocol/decode (pr-str message))))))
    (doseq [stocks [-1 4 1.5 nil "3" ##Inf ##NaN]]
      (is (not (valid-snapshot? (assoc-in snapshot (conj path :stocks) stocks)))))
    (doseq [[lifecycle stocks] [[:unknown 3] [nil 3]
                                [:active 0] [:respawning 0] [:eliminated 1]]]
      (is (not (valid-snapshot?
                 (update-in snapshot path assoc :lifecycle lifecycle :stocks stocks
                                      :respawn-seconds-left (if (= lifecycle :respawning) 3 0))))))))

(deftest snapshots-require-finite-stored-spawns
  (let [snapshot {:type :snapshot :tick 0 :world (match-helpers/running-world game/initial-world)}]
    (doseq [field [:spawn-x :spawn-y]
            value [nil "160" ##Inf ##NaN]]
      (is (not (valid-snapshot?
                 (assoc-in snapshot [:world :players :player-1 field] value)))))))

(deftest snapshots-bound-protection-to-active-lives
  (let [snapshot {:type :snapshot :tick 0 :world (match-helpers/running-world game/initial-world)}
        path [:world :players :player-1]]
    (doseq [seconds [0 0.025 2]]
      (is (valid-snapshot? (assoc-in snapshot (conj path :protection-seconds-left) seconds))))
    (doseq [seconds [-1 2.001 ##Inf ##NaN nil "2"]]
      (is (not (valid-snapshot? (assoc-in snapshot (conj path :protection-seconds-left) seconds)))))
    (doseq [state [:respawning :eliminated]]
      (is (not (valid-snapshot?
                 (update-in snapshot path assoc :stocks (if (= state :eliminated) 0 1)
                            :lifecycle state :respawn-seconds-left (if (= state :respawning) 3 0)
                            :protection-seconds-left 1)))))))

(ns clojure-arena.match-clock-test
  (:require [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest start-retains-the-full-clock-and-only-running-ticks-consume-time
  (let [waiting (countdown/entrants)
        countdown-world (match/step waiting {} 2.975)
        started (match/step countdown-world {} 0.025)
        before (nth (iterate #(match/step % {} 0.025) started) 7199)
        expired (match/step before {} 0.025)
        later (match/step expired {} 0.025)]
    (is (= 0 (get-in countdown-world [:match :time-seconds-left])))
    (is (= 180 (get-in started [:match :time-seconds-left])))
    (is (< 0.0249 (get-in before [:match :time-seconds-left]) 0.0251))
    (is (zero? (get-in expired [:match :time-seconds-left])))
    (is (zero? (get-in later [:match :time-seconds-left])))
    (is (= [3 3] (mapv :stocks (vals (:players expired)))))))

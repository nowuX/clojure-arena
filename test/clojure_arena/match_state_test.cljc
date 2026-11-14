(ns clojure-arena.match-state-test
  (:require [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn waiting-world []
  (match/add-player (assoc game/initial-world :players {} :match match/initial-match)
                    :player-1 (game/make-player "#22d3ee" 340 160)))

(deftest waiting-is-owned-public-state-without-gameplay
  (let [world (waiting-world)
        advanced (nth (iterate #(match/step % {:player-1 {:horizontal 1 :jump? true :attack? true}} 0.025)
                               world) 200)
        message {:type :snapshot :tick 200 :world advanced}]
    (is (= world advanced))
    (is (= [0 :waiting] ((juxt :stocks :lifecycle) (get-in world [:players :player-1]))))
    (is (not (game/active-player? (get-in world [:players :player-1]))))
    (is (= {} (get-in world [:match :contenders])))
    (is (nil? (get-in world [:match :result])))
    (is (protocol/snapshot-message? message))
    (is (= message (protocol/decode (pr-str message))))))

(deftest snapshot-requires-exact-public-match-data
  (let [message {:type :snapshot :tick 0 :world (waiting-world)}]
    (doseq [invalid [(update message :world dissoc :match)
                     (assoc-in message [:world :match :phase] :unknown)
                     (assoc-in message [:world :match :connection-token] :secret)
                     (assoc-in message [:world :match :id] -1)
                     (assoc-in message [:world :match :intermission-seconds-left] nil)
                     (assoc-in message [:world :match :contenders] [])
                     (assoc-in message [:world :players :player-1 :stocks] 3)]]
      (is (not (protocol/snapshot-message? invalid))))))

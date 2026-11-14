(ns clojure-arena.room-stock-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]))

(deftest stock-loss-keeps-session-ownership-and-consumes-inactive-actions
  (let [joined (:room (room/apply-room-message room/initial-room
                                              {:type :join :connection-token :session}))
        before (-> joined
                   (assoc-in [:world :players :player-1 :x] 900)
                   (assoc-in [:inputs-by-player :player-1]
                             {:horizontal 1 :jump? true :drop? true :attack? true}))
        result (room/apply-room-message before {:type :tick})
        after (:room result)
        next-result (room/apply-room-message after {:type :tick})
        player (get-in after [:world :players :player-1])]
    (is (= (:members joined) (:members after)))
    (is (= 2 (:next-player-number after)))
    (is (= [2 :respawning] ((juxt :stocks :lifecycle) player)))
    (is (= (assoc protocol/neutral-input :horizontal 1)
           (get-in after [:inputs-by-player :player-1])))
    (is (= (select-keys player [:stocks :lifecycle :x :y :vx :vy])
           (select-keys (get-in next-result [:room :world :players :player-1])
                        [:stocks :lifecycle :x :y :vx :vy])))
    (is (= [:session] (:connection-tokens (first (:effects result)))))
    (is (protocol/snapshot-message? (:message (first (:effects result)))))
    (is (= (:world after) (:world (:message (first (:effects result))))))))

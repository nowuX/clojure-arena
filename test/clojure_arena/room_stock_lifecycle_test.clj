(ns clojure-arena.room-stock-lifecycle-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]))

(deftest stock-loss-keeps-session-ownership-and-consumes-inactive-actions
  (let [joined (reduce (fn [state token]
                         (:room (room/apply-room-message state {:type :join :connection-token token})))
                       room/initial-room [:session :partner])
        before (-> (match-helpers/active-room joined)
                   (assoc-in [:world :players :player-1 :x] 900)
                   (assoc-in [:inputs-by-player :player-1]
                             {:horizontal 1 :jump? true :drop? true :attack? true}))
        result (room/apply-room-message before {:type :tick})
        after (:room result)
        next-result (room/apply-room-message after {:type :tick})
        player (get-in after [:world :players :player-1])]
    (is (= (:members joined) (:members after)))
    (is (= 3 (:next-player-number after)))
    (is (= [2 :respawning] ((juxt :stocks :lifecycle) player)))
    (is (= (assoc protocol/neutral-input :horizontal 1)
           (get-in after [:inputs-by-player :player-1])))
    (is (= (select-keys player [:stocks :lifecycle :x :y :vx :vy])
           (select-keys (get-in next-result [:room :world :players :player-1])
                        [:stocks :lifecycle :x :y :vx :vy])))
    (is (= #{:session :partner} (set (:connection-tokens (first (:effects result))))))
    (is (protocol/snapshot-message? (:message (first (:effects result)))))
    (is (= (:world after) (:world (:message (first (:effects result))))))))

(defn- join-room [state connection-token]
  (:room (room/apply-room-message state
                                  {:type :join
                                   :connection-token connection-token})))

(defn- set-input [state connection-token input]
  (:room (room/apply-room-message state
                                  {:type :input
                                   :connection-token connection-token
                                   :input input})))

(defn- tick-room [state]
  (room/apply-room-message state {:type :tick}))

(deftest inactive-pulses-are-consumed-while-room-membership-and-snapshots-continue
  (let [tokens [:connection-a :connection-b :connection-c :connection-d]
        joined (reduce join-room room/initial-room tokens)
        inactive-player (-> (get-in joined [:world :players :player-1])
                            (assoc :x -200 :stocks 2 :lifecycle :respawning :respawn-seconds-left 3))
        prepared (-> (match-helpers/active-room joined)
                     (assoc-in [:world :players :player-1] inactive-player)
                     (set-input :connection-a {:horizontal 1
                                               :jump? true
                                               :drop? true
                                               :attack? true}))
        first-result (tick-room prepared)
        after-first (:room first-result)
        first-player (get-in after-first [:world :players :player-1])
        first-input (get-in after-first [:inputs-by-player :player-1])
        [snapshot-effect] (:effects first-result)
        snapshot (:message snapshot-effect)
        decoded (protocol/decode (pr-str snapshot))
        second-result (tick-room after-first)
        after-second (:room second-result)]
    (testing "an inactive pulse is discarded and does not step or charge the body"
      (is (= 1 (:tick after-first)))
      (is (= (dissoc inactive-player :respawn-seconds-left)
             (dissoc first-player :respawn-seconds-left)))
      (is (= 2 (:stocks first-player)))
      (is (= :respawning (:lifecycle first-player)))
      (is (= {:horizontal 1 :jump? false :drop? false :attack? false}
             first-input))
      (is (= (dissoc first-player :respawn-seconds-left)
             (dissoc (get-in after-second [:world :players :player-1]) :respawn-seconds-left)))
      (is (= first-input (get-in after-second [:inputs-by-player :player-1])))
      (is (= 2 (:tick after-second))))
    (testing "the connected room continues publishing exact snapshots"
      (is (= (set tokens) (set (keys (:members after-first)))))
      (is (= #{:player-1 :player-2 :player-3 :player-4}
             (set (keys (get-in after-first [:world :players])))))
      (is (= #{:player-1 :player-2 :player-3 :player-4}
             (set (keys (:inputs-by-player after-first)))))
      (is (= (set tokens) (set (:connection-tokens snapshot-effect))))
      (is (= #{:type :tick :world} (set (keys snapshot))))
      (is (= #{:blast-bounds :platforms :players :match}
             (set (keys (:world snapshot)))))
      (is (= snapshot decoded))
      (is (protocol/snapshot-message? decoded))
      (is (protocol/server-message? decoded))
      (is (= :green (get-in after-first [:members :connection-d :color])))
      (is (= "#4ade80" (get-in after-first [:world :players :player-4 :color])))
      (is (= [3 :active]
             ((juxt :stocks :lifecycle)
              (get-in after-first [:world :players :player-4])))))))


(deftest room-snapshots-follow-loss-return-and-protection-expiry
  (let [joined (reduce join-room room/initial-room [:a :b])
        prepared (-> (match-helpers/active-room joined)
                     (assoc-in [:world :players :player-1 :x] 900)
                     (set-input :a {:horizontal 1 :jump? true :drop? true :attack? true}))
        loss (tick-room prepared)
        states (take 121 (iterate #(tick-room (:room %)) loss))
        before-return (nth states 119)
        returned (nth states 120)
        fresh (get-in returned [:room :world :players :player-1])
        expired (nth (iterate #(tick-room (:room %)) returned) 80)]
    (is (= [2 :respawning 3] ((juxt :stocks :lifecycle :respawn-seconds-left)
                               (get-in loss [:room :world :players :player-1]))))
    (is (= :respawning (get-in before-return [:room :world :players :player-1 :lifecycle])))
    (is (= [2 :active 0 2] ((juxt :stocks :lifecycle :respawn-seconds-left :protection-seconds-left) fresh)))
    (is (= [340 160] ((juxt :x :y) fresh)))
    (is (= 0 (:melee-strike-id fresh)))
    (is (= 121 (get-in returned [:room :tick])))
    (is (= 201 (get-in expired [:room :tick])))
    (is (zero? (get-in expired [:room :world :players :player-1 :protection-seconds-left])))
    (is (= (:members joined) (get-in returned [:room :members])))
    (doseq [state [loss before-return returned expired]
            effect (:effects state)]
      (is (= #{:a :b} (set (:connection-tokens effect))))
      (is (protocol/snapshot-message? (:message effect)))
      (is (= (:message effect) (protocol/decode (pr-str (:message effect))))))))

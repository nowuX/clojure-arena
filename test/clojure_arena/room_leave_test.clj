(ns clojure-arena.room-leave-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]))

(defn- join-room [state connection-token]
  (room/apply-room-message state
                           {:type :join
                            :connection-token connection-token}))

(defn- leave-room [state connection-token]
  (room/apply-room-message state
                           {:type :leave
                            :connection-token connection-token}))

(defn- input-room [state connection-token input]
  (room/apply-room-message state
                           {:type :input
                            :connection-token connection-token
                            :input input}))

(defn- join-many [connection-tokens]
  (reduce (fn [state connection-token]
            (:room (join-room state connection-token)))
          room/initial-room
          connection-tokens))

(deftest leave-message-requires-an-exact-map-and-non-nil-token-test
  (let [state (:room (join-room room/initial-room :connection-a))
        invalid-messages [nil
                          {}
                          {:type :leave}
                          {:type :leave :connection-token nil}
                          {:type :leave :connection-token :connection-a
                           :extra true}
                          {:type :leave :connection-token :connection-a
                           :token :connection-a}]]
    (doseq [message invalid-messages]
      (let [result (room/apply-room-message state message)]
        (is (identical? state (:room result))
            (str "invalid leave should preserve room identity for "
                 (pr-str message)))
        (is (= {:room state :effects []} result)
            (str "invalid leave should have no effects for "
                 (pr-str message)))))
    (let [result (leave-room state :unknown-connection)]
      (is (identical? state (:room result)))
      (is (= {:room state :effects []} result)))))

(deftest leave-removes-membership-player-and-input-in-one-pure-transition-test
  (let [joined (join-many [:connection-a :connection-b :connection-c])
        input-a (assoc protocol/neutral-input :horizontal -1 :drop? true)
        input-b (assoc protocol/neutral-input :horizontal 1 :jump? true :attack? true)
        input-c (assoc protocol/neutral-input :attack? true)
        prepared (-> joined
                     (assoc :tick 23)
                     (assoc :inputs-by-player
                            {:player-1 input-a
                             :player-2 input-b
                             :player-3 input-c})
                     (assoc-in [:world :players :player-2 :x] 512))
        original-platforms (get-in prepared [:world :platforms])
        player-a (get-in prepared [:world :players :player-1])
        player-c (get-in prepared [:world :players :player-3])
        result (leave-room prepared :connection-b)
        next-room (:room result)]
    (is (= {:room (-> prepared
                      (update :members dissoc :connection-b)
                      (update-in [:world :players] dissoc :player-2)
                      (update :inputs-by-player dissoc :player-2))
            :effects []}
           result))
    (is (= 23 (:tick next-room)))
    (is (= 4 (:next-player-number next-room)))
    (is (= #{:connection-a :connection-c} (set (keys (:members next-room)))))
    (is (= #{:player-1 :player-3}
           (set (keys (get-in next-room [:world :players])))))
    (is (= #{:player-1 :player-3} (set (keys (:inputs-by-player next-room)))))
    (is (identical? original-platforms (get-in next-room [:world :platforms])))
    (is (identical? player-a (get-in next-room [:world :players :player-1])))
    (is (identical? player-c (get-in next-room [:world :players :player-3])))
    (is (identical? input-a (get-in next-room [:inputs-by-player :player-1])))
    (is (identical? input-c (get-in next-room [:inputs-by-player :player-3])))))

(deftest one-through-four-members-can-all-leave-test
  (doseq [player-count (range 1 5)]
    (testing (str player-count " member(s)")
      (let [tokens (mapv #(keyword (str "connection-" %))
                         (range player-count))
            joined (join-many tokens)
            next-player-number (:next-player-number joined)
            final-room (reduce (fn [state connection-token]
                                 (:room (leave-room state connection-token)))
                               joined
                               tokens)]
        (is (= {} (:members final-room)))
        (is (= {} (get-in final-room [:world :players])))
        (is (= {} (:inputs-by-player final-room)))
        (is (= next-player-number (:next-player-number final-room)))
        (is (= (:tick joined) (:tick final-room)))))))

(deftest stale-input-and-repeated-leave-cannot-affect-a-new-player-with-reused-color-test
  (let [old-token :old-connection
        new-token :new-connection
        old-joined (:room (join-room room/initial-room old-token))
        old-input (assoc protocol/neutral-input :horizontal 1 :jump? true)
        old-active (:room (input-room old-joined old-token old-input))
        after-leave (:room (leave-room old-active old-token))
        repeated-leave (leave-room after-leave old-token)
        new-joined (join-room after-leave new-token)
        current-room (:room new-joined)
        stale-input (input-room current-room old-token
                                (assoc protocol/neutral-input :horizontal -1 :attack? true))
        stale-leave (leave-room (:room stale-input) old-token)]
    (is (identical? after-leave (:room repeated-leave)))
    (is (= {:room after-leave :effects []} repeated-leave))
    (is (= {:player-id :player-2 :color :cyan}
           (get-in current-room [:members new-token])))
    (is (= #{:player-2} (set (keys (get-in current-room [:world :players])))))
    (is (= protocol/neutral-input
           (get-in current-room [:inputs-by-player :player-2])))
    (is (= current-room (:room stale-input)))
    (is (identical? current-room (:room stale-input)))
    (is (= {:room current-room :effects []} stale-leave))
    (is (identical? current-room (:room stale-leave)))
    (is (= new-token (first (keys (get-in stale-leave [:room :members])))))
    (is (= :player-2
           (get-in stale-leave [:room :members new-token :player-id])))))

(deftest next-tick-snapshot-contains-only-remaining-players-and-recipients-test
  (let [joined (join-many [:connection-a :connection-b])
        after-leave (:room (leave-room joined :connection-a))
        result (room/apply-room-message after-leave {:type :tick})
        snapshot (first (:effects result))]
    (is (= 1 (get-in result [:room :tick])))
    (is (= [{:connection-tokens [:connection-b]
             :message {:type :snapshot
                       :tick 1
                       :world (get-in result [:room :world])}}]
           (:effects result)))
    (is (= [:connection-b] (:connection-tokens snapshot)))
    (is (= #{:player-2} (set (keys (get-in snapshot [:message :world :players])))))
    (is (not (contains? (get-in snapshot [:message :world :players]) :player-1)))
    (is (= 1 (count (:members (:room result)))))))

(deftest leaving-a-full-room-allows-normal-admission-with-a-fresh-player-id-test
  (let [full-room (join-many [:connection-a :connection-b
                              :connection-c :connection-d])
        rejected (join-room full-room :connection-e)
        after-leave (:room (leave-room full-room :connection-b))
        admitted (join-room after-leave :connection-e)
        final-room (:room admitted)]
    (is (= [{:connection-token :connection-e
             :message {:type :room-full}}]
           (:effects rejected)))
    (is (identical? full-room (:room rejected)))
    (is (= [{:connection-token :connection-e
             :message {:type :identity
                       :player-id :player-5
                       :color :crimson}}]
           (:effects admitted)))
    (is (= 6 (:next-player-number final-room)))
    (is (= :player-5 (get-in final-room [:members :connection-e :player-id])))
    (is (= :crimson (get-in final-room [:members :connection-e :color])))
    (is (= (assoc (room/make-spawn-player :crimson) :stocks 0 :lifecycle :waiting)
           (get-in final-room [:world :players :player-5])))
    (is (= #{:connection-a :connection-c :connection-d :connection-e}
           (set (keys (:members final-room))))
        "the freed palette color should be assigned from current membership")))

(deftest leave-transition-room-remains-edn-valid-test
  (let [joined (join-many [:connection-a :connection-b])
        left (:room (leave-room joined :connection-a))]
    (is (= left (edn/read-string (pr-str left))))
    (is (= (:world left) (edn/read-string (pr-str (:world left)))))))

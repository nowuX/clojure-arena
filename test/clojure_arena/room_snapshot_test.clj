(ns clojure-arena.room-snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]))

(defn- join-room [state connection-token]
  (:room (room/apply-room-message state
                                  {:type :join
                                   :connection-token connection-token})))

(defn- tick-room [state]
  (room/apply-room-message state {:type :tick}))

(defn- snapshot-effect [result]
  (first (:effects result)))

(deftest current-factory-and-real-physics-snapshots-roundtrip-through-edn-test
  (testing "a complete current factory scene survives the wire roundtrip"
    (let [message {:type :snapshot :tick 0 :world game/initial-world}
          decoded (protocol/decode (pr-str message))]
      (is (= message decoded))
      (is (protocol/snapshot-message? decoded))
      (is (protocol/server-message? decoded))))
  (testing "the snapshot emitted after a real room physics tick is wire-safe"
    (let [joined (join-room room/initial-room :connection-a)
          before-y (get-in joined [:world :players :player-1 :y])
          result (tick-room joined)
          next-room (:room result)
          message (:message (snapshot-effect result))
          decoded (protocol/decode (pr-str message))]
      (is (= 1 (:tick next-room)))
      (is (> (get-in next-room [:world :players :player-1 :y]) before-y)
          "the real physics step should move the spawned player")
      (is (= message decoded))
      (is (protocol/snapshot-message? decoded))
      (is (protocol/server-message? decoded)))))

(deftest ticks-broadcast-one-snapshot-only-for-admitted-members-test
  (let [empty-result (tick-room room/initial-room)]
    (is (= [] (:effects empty-result))
        "empty rooms should advance without broadcasting"))
  (doseq [member-count (range 1 5)]
    (let [tokens (mapv #(keyword (str "connection-" %)) (range member-count))
          prepared (reduce join-room room/initial-room tokens)
          result (tick-room prepared)
          next-room (:room result)
          expected-effect {:connection-tokens (vec (keys (:members next-room)))
                           :message {:type :snapshot
                                     :tick (:tick next-room)
                                     :world (:world next-room)}}]
      (is (= [expected-effect] (:effects result))
          (str "a tick with " member-count " members emits one captured broadcast"))
      (is (= member-count
             (count (:connection-tokens (snapshot-effect result)))))
      (is (= member-count
             (count (get-in (snapshot-effect result) [:message :world :players]))))))
  (let [four-members (reduce join-room room/initial-room
                             [:connection-1 :connection-2 :connection-3 :connection-4])
        fifth-join (room/apply-room-message
                    four-members
                    {:type :join :connection-token :connection-5})
        result (tick-room (:room fifth-join))]
    (is (= 4 (count (:members (:room fifth-join))))
        "the fifth join remains rejected by the existing room capacity")
    (is (= 4 (count (:connection-tokens (snapshot-effect result))))
        "a full room broadcasts to the four admitted tokens only")))

(deftest tick-effect-captures-one-next-room-tick-world-and-recipient-vector-test
  (let [prepared (reduce join-room room/initial-room [:alpha :beta :gamma])
        result (tick-room prepared)
        next-room (:room result)
        [effect] (:effects result)]
    (is (= {:connection-tokens (vec (keys (:members next-room)))
            :message {:type :snapshot
                      :tick (:tick next-room)
                      :world (:world next-room)}}
           effect))
    (is (= (:tick next-room) (get-in effect [:message :tick])))
    (is (identical? (:world next-room) (get-in effect [:message :world]))
        "the wire world should be the exact immutable world committed by this transition")))

(defn- await-agent! [room-agent]
  (let [finished? (await-for 2000 room-agent)]
    (is finished? "the room agent should finish before the deadline")
    (is (nil? (agent-error room-agent)) "the room agent should not fail")
    finished?))

(deftest post-commit-watch-sees-captured-snapshot-and-delayed-inspection-keeps-its-tick-test
  (let [room-ref (atom nil)
        watch-records (atom [])
        room-agent (room/make-room
                    (fn [effects]
                      (swap! watch-records conj
                             {:effects effects
                              :committed-room (:room (deref @room-ref))})))]
    (try
      (reset! room-ref room-agent)
      (room/enqueue-room-message! room-agent
                                  {:type :join :connection-token :connection-a})
      (await-agent! room-agent)
      (room/enqueue-room-message! room-agent {:type :tick})
      (await-agent! room-agent)
      (room/enqueue-room-message! room-agent {:type :tick})
      (await-agent! room-agent)
      (let [snapshot-records
            (filterv (fn [{:keys [effects]}]
                       (= :snapshot (get-in (first effects) [:message :type])))
                     @watch-records)
            first-record (first snapshot-records)
            second-record (second snapshot-records)
            first-effect (first (:effects first-record))
            second-effect (first (:effects second-record))]
        (is (= 2 (count snapshot-records)))
        (doseq [[record effect expected-tick]
                [[first-record first-effect 1]
                 [second-record second-effect 2]]]
          (is (= expected-tick (get-in record [:committed-room :tick])))
          (is (= expected-tick (get-in effect [:message :tick])))
          (is (= (get-in record [:committed-room :world])
                 (get-in effect [:message :world])))
          (is (identical? (get-in record [:committed-room :world])
                          (get-in effect [:message :world]))
              "the post-commit watch should receive the world from its committed value")
          (is (= [:connection-a] (:connection-tokens effect))))
        (is (= 2 (get-in @room-agent [:room :tick]))
            "inspection occurs after a later tick has already committed")
        (is (= 1 (get-in first-effect [:message :tick]))
            "a saved earlier snapshot must keep its captured tick"))
      (finally
        (await-for 2000 room-agent)
        (reset! room-ref nil)))))

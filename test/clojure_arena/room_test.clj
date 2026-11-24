(ns clojure-arena.room-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room])
  (:import [java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit]))

(defn- join-room [state connection-token]
  (room/apply-room-message state
                           {:type :join
                            :connection-token connection-token}))

(defn- input-message [connection-token input]
  {:type :input
   :connection-token connection-token
   :input input})

(defn- await-agent! [room-agent]
  (is (await-for 2000 room-agent)
      "room agent should finish its queued actions before the deadline")
  (is (nil? (agent-error room-agent))
      "room actions and post-commit delivery should not fail the agent"))

(defn- waiting-player [color x]
  (assoc (game/make-player color x 160)
         :lifecycle :waiting
         :stocks 0))

(deftest apply-room-message-is-pure-and-deterministic-test
  (let [initial room/initial-room
        first-result (join-room initial :connection-a)
        second-result (join-room initial :connection-a)]
    (is (= first-result second-result))
    (is (= room/initial-room initial))
    (is (identical? initial
                    (:room (room/apply-room-message initial {:type :unknown}))))
    (is (= {:room (-> room/initial-room
                      (assoc-in [:members :connection-a]
                                {:player-id :player-1 :color :cyan})
                      (assoc-in [:inputs-by-player :player-1]
                                protocol/neutral-input)
                      (assoc :next-player-number 2)
                      (assoc-in [:world :players :player-1]
                                (waiting-player "#22d3ee" 340)))
            :effects [{:connection-token :connection-a
                       :message {:type :identity
                                 :player-id :player-1
                                 :color :cyan}}]}
           first-result))
    (is (not-any? fn?
                  (tree-seq coll? seq first-result)))))

(deftest initial-room-has-no-player-inputs-test
  (is (= 0 (:tick room/initial-room)))
  (is (= {} (:inputs-by-player room/initial-room)))
  (is (= match/initial-match (get-in room/initial-room [:world :match])))
  (is (= protocol/neutral-input
         protocol/neutral-input)))

(deftest first-four-joins-receive-free-colors-and-monotonic-identities-test
  (let [results (reduce (fn [acc connection-token]
                          (let [result (join-room (:room acc) connection-token)]
                            {:room (:room result)
                             :effects (conj (:effects acc) (first (:effects result)))}))
                        {:room room/initial-room :effects []}
                        [:connection-a :connection-b :connection-c :connection-d])]
    (is (= protocol/player-colors
           (mapv (comp :color :message) (:effects results))))
    (is (= [:player-1 :player-2 :player-3 :player-4]
           (mapv (comp :player-id :message) (:effects results))))
    (is (= 5 (get-in results [:room :next-player-number])))
    (is (= #{:connection-a :connection-b :connection-c :connection-d}
           (set (keys (get-in results [:room :members])))))))

(deftest capacity-rejections-preserve-the-exact-room-and-counter-test
  (let [full-room (reduce (fn [state connection-token]
                            (:room (join-room state connection-token)))
                          room/initial-room
                          [:connection-a :connection-b :connection-c :connection-d])]
    (doseq [connection-token [:connection-e :connection-f]]
      (let [result (join-room full-room connection-token)]
        (is (identical? full-room (:room result)))
        (is (= full-room (:room result)))
        (is (= 5 (get-in result [:room :next-player-number])))
        (is (= [{:connection-token connection-token
                 :message {:type :room-full}}]
               (:effects result)))))))

(deftest duplicate-joins-return-existing-identity-even-at-capacity-test
  (let [full-room (reduce (fn [state connection-token]
                            (:room (join-room state connection-token)))
                          room/initial-room
                          [:connection-a :connection-b :connection-c :connection-d])
        existing-member (get-in full-room [:members :connection-a])
        duplicate (join-room full-room :connection-a)]
    (is (identical? full-room (:room duplicate)))
    (is (= full-room (:room duplicate)))
    (is (= 5 (get-in duplicate [:room :next-player-number])))
    (is (= [{:connection-token :connection-a
             :message {:type :identity
                       :player-id (:player-id existing-member)
                       :color (:color existing-member)}}]
           (:effects duplicate)))))

(deftest player-number-counter-is-independent-from-palette-position-test
  (let [seeded-room (-> (:room (join-room room/initial-room :existing))
                        (assoc :next-player-number 41))
        result (join-room seeded-room :new-connection)]
    (is (= (-> seeded-room
               (assoc-in [:members :new-connection]
                         {:player-id :player-41 :color :crimson})
               (assoc-in [:inputs-by-player :player-41]
                         protocol/neutral-input)
               (assoc :next-player-number 42)
               (assoc-in [:world :players :player-41]
                         (waiting-player "#e11d48" 380))
               (assoc-in [:world :match]
                         {:id 0 :phase :countdown
                          :countdown-seconds-left 3
                          :time-seconds-left 0 :intermission-seconds-left 0
                          :result nil :contenders {}}))
           (:room result)))
    (is (= [{:connection-token :new-connection
             :message {:type :identity
                       :player-id :player-41
                       :color :crimson}}]
           (:effects result)))))

(deftest unsupported-and-missing-token-messages-are-ignored-test
  (doseq [message [nil
                   {}
                   {:type :ping :connection-token :connection-a}
                   {:type :join}
                   {:type :join :connection-token nil}
                   {:type :join :connection-token :connection-a :extra true}]]
    (let [result (room/apply-room-message room/initial-room message)]
      (is (identical? room/initial-room (:room result))
          (str "room should be unchanged for " (pr-str message)))
      (is (= {:room room/initial-room :effects []} result)
          (str "effects should be empty for " (pr-str message))))))

(deftest make-room-delivers-exact-captured-effects-after-commit-in-fifo-order-test
  (let [room-ref (atom nil)
        callback-records (atom [])
        room-agent (room/make-room
                    (fn [effects]
                      (let [committed-room (:room (deref (deref room-ref)))
                            members (:members committed-room)
                            players (get-in committed-room [:world :players])]
                        (swap! callback-records conj
                               {:effects effects
                                :committed-member-count (count members)
                                :committed-world-player-count (count players)
                                :committed-member-player-ids
                                (set (map :player-id (vals members)))
                                :committed-world-player-ids (set (keys players))}))))]
    (try
      (reset! room-ref room-agent)
      (is (= {:room room/initial-room :effects []} @room-agent))
      (is (= #{:room :effects} (set (keys @room-agent))))
      (room/enqueue-room-message! room-agent {:type :join
                                               :connection-token :first})
      (room/enqueue-room-message! room-agent {:type :join
                                               :connection-token :second})
      (room/enqueue-room-message! room-agent {:type :join
                                               :connection-token :third})
      (await-agent! room-agent)
      (is (= [1 2 3] (mapv :committed-member-count @callback-records)))
      (is (= [1 2 3] (mapv :committed-world-player-count @callback-records)))
      (is (every? #(= (:committed-member-player-ids %)
                      (:committed-world-player-ids %))
                  @callback-records)
          "post-commit callbacks should observe matching member and world player IDs")
      (is (= [[{:connection-token :first
                :message {:type :identity :player-id :player-1 :color :cyan}}]
              [{:connection-token :second
                :message {:type :identity :player-id :player-2 :color :crimson}}]
              [{:connection-token :third
                :message {:type :identity :player-id :player-3 :color :amber}}]]
             (mapv :effects @callback-records)))
      (is (= {:room (-> room/initial-room
                        (assoc :members
                               {:first {:player-id :player-1 :color :cyan}
                                :second {:player-id :player-2 :color :crimson}
                                :third {:player-id :player-3 :color :amber}})
                        (assoc :inputs-by-player
                               {:player-1 protocol/neutral-input
                                :player-2 protocol/neutral-input
                                :player-3 protocol/neutral-input})
                        (assoc :next-player-number 4)
                        (assoc-in [:world :players]
                                  {:player-1 (waiting-player "#22d3ee" 340)
                                   :player-2 (waiting-player "#e11d48" 380)
                                   :player-3 (waiting-player "#f59e0b" 420)})
                        (assoc-in [:world :match]
                                  {:id 0 :phase :countdown
                                   :countdown-seconds-left 3
                                   :time-seconds-left 0 :intermission-seconds-left 0 :result nil
                                   :contenders {}}))
              :effects [{:connection-token :third
                         :message {:type :identity
                                   :player-id :player-3
                                   :color :amber}}]}
             @room-agent))
      (finally
        (reset! room-ref nil)))))

(deftest waiting-input-retains-direction-and-drops-actions-without-stepping-world-test
  (let [joined-a (:room (join-room room/initial-room :connection-a))
        joined-b (:room (join-room joined-a :connection-b))
        input-a (assoc protocol/neutral-input :horizontal 1 :jump? true)
        before-world (:world joined-b)
        result (room/apply-room-message
                joined-b (input-message :connection-a input-a))]
    (is (= {:room (assoc-in joined-b [:inputs-by-player :player-1]
                            (assoc protocol/neutral-input :horizontal 1))
            :effects []}
           result))
    (is (= before-world (get-in result [:room :world]))
        "accepting input must not advance or otherwise mutate the world")
    (is (identical? before-world (get-in result [:room :world]))
        "input changes should preserve the existing world value")
    (is (= protocol/neutral-input
           (get-in result [:room :inputs-by-player :player-2])))))

(deftest neutral-input-clears-horizontal-but-retains-pending-actions-test
  (let [joined (-> (:room (join-room room/initial-room :connection-a))
                   (update-in [:world :players :player-1]
                              assoc :lifecycle :active :stocks 3)
                   match-helpers/running-room)
        tap (assoc protocol/neutral-input :horizontal -1 :jump? true :attack? true)
        tap-result (room/apply-room-message joined
                                           (input-message :connection-a tap))
        neutral-result (room/apply-room-message
                        (:room tap-result)
                        (input-message :connection-a protocol/neutral-input))]
    (is (= (assoc protocol/neutral-input :jump? true :attack? true)
           (get-in neutral-result [:room :inputs-by-player :player-1]))
        "a neutral frame replaces direction while pending action flags stay set")
    (is (= [] (:effects tap-result)))
    (is (= [] (:effects neutral-result)))
    (is (= (:world joined) (get-in neutral-result [:room :world])))))

(deftest rejected-and-unknown-player-inputs-preserve-the-exact-room-test
  (let [joined (:room (join-room room/initial-room :connection-a))
        valid-input (assoc protocol/neutral-input :horizontal 1 :jump? true)
        messages [(input-message :unknown-connection valid-input)
                  (input-message :connection-a (assoc valid-input :horizontal 2))
                  (input-message :connection-a (assoc valid-input :jump? 1))
                  (input-message :connection-a (assoc valid-input :player-id :player-2))
                  (input-message :connection-a (assoc valid-input
                                                       :connection-token :forged))
                  (input-message :connection-a (assoc valid-input :position [9 9]))
                  (input-message :connection-a (assoc valid-input :velocity [9 9]))
                  (input-message :connection-a (dissoc valid-input :attack?))
                  (assoc (input-message :connection-a valid-input) :extra true)
                  {:type :input :connection-token :connection-a}
                  {:type :input :connection-token :connection-a
                   :input valid-input :extra true}]]
    (doseq [message messages]
      (let [result (room/apply-room-message joined message)]
        (is (identical? joined (:room result))
            (str "rejected input should preserve room identity for "
                 (pr-str message)))
        (is (= {:room joined :effects []} result)
            (str "rejected input should have no effects for " (pr-str message)))))))

(deftest queued-concurrent-inputs-remain-owned-by-their-members-test
  (let [deliveries (atom [])
        room-agent (room/make-room #(swap! deliveries into %))
        submitters [:connection-a :connection-b]
        ready (CountDownLatch. (count submitters))
        start (CountDownLatch. 1)
        finished (CountDownLatch. (count submitters))
        failures (atom [])
        inputs {:connection-a (assoc protocol/neutral-input :horizontal 1 :jump? true)
                :connection-b (assoc protocol/neutral-input :horizontal -1 :drop? true :attack? true)}
        threads (mapv (fn [connection-token]
                        (doto (Thread.
                               ^Runnable
                               (reify Runnable
                                 (run [_]
                                   (.countDown ready)
                                   (try
                                     (when-not (.await start 2 TimeUnit/SECONDS)
                                       (throw (ex-info "start latch timed out" {})))
                                     (room/enqueue-room-message!
                                      room-agent
                                      (input-message connection-token
                                                     (get inputs connection-token)))
                                     (catch Throwable error
                                       (swap! failures conj error))
                                     (finally
                                       (.countDown finished))))))
                          (.setDaemon true)))
                      submitters)]
    (try
      (doseq [connection-token submitters]
        (room/enqueue-room-message! room-agent
                                    {:type :join
                                     :connection-token connection-token}))
      (await-agent! room-agent)
      (let [world-before (get-in @room-agent [:room :world])]
        (doseq [thread threads]
          (.start thread))
        (is (.await ready 2 TimeUnit/SECONDS)
            "all concurrent input submitters should reach the start latch")
        (.countDown start)
        (is (.await finished 2 TimeUnit/SECONDS)
            "concurrent input submitters should enqueue before the deadline")
        (is (empty? @failures) (pr-str @failures))
        (await-agent! room-agent)
        (is (= {:player-1 (assoc protocol/neutral-input :horizontal 1)
                :player-2 (assoc protocol/neutral-input :horizontal -1)}
               (get-in @room-agent [:room :inputs-by-player]))
            "concurrent waiting inputs should retain direction and clear one-shot actions")
        (is (= world-before (get-in @room-agent [:room :world]))
            "queued input updates must not advance the world")
        (is (= 2 (count @deliveries))
            "input updates must not produce socket effects"))
      (finally
        (.countDown start)))))

(deftest queued-duplicate-replies-without-allocating-or-replaying-on-waits-test
  (let [deliveries (atom [])
        room-agent (room/make-room #(swap! deliveries into %))
        message {:type :join :connection-token :same-connection}]
    (room/enqueue-room-message! room-agent message)
    (await-agent! room-agent)
    (let [committed-room (:room @room-agent)]
      (room/enqueue-room-message! room-agent message)
      (await-agent! room-agent)
      (is (identical? committed-room (:room @room-agent)))
      (is (= 2 (get-in @room-agent [:room :next-player-number])))
      (is (= [{:connection-token :same-connection
               :message {:type :identity :player-id :player-1 :color :cyan}}
              {:connection-token :same-connection
               :message {:type :identity :player-id :player-1 :color :cyan}}]
             @deliveries)))))

(deftest concurrent-queued-joins-stay-within-capacity-and-deduplicate-test
  (let [request-tokens [:a :b :c :d :e :f :a :b :e :g]
        request-count (count request-tokens)
        ready (CountDownLatch. request-count)
        start (CountDownLatch. 1)
        finished (CountDownLatch. request-count)
        failures (atom [])
        deliveries (LinkedBlockingQueue.)
        room-ref (atom nil)
        room-agent (room/make-room
                    (fn [effects]
                      (let [committed-room (:room (deref (deref room-ref)))
                            members (:members committed-room)
                            players (get-in committed-room [:world :players])]
                        (.add deliveries
                              {:effects effects
                               :members members
                               :member-player-ids
                               (set (map :player-id (vals members)))
                               :world-player-ids (set (keys players))
                               :next-player-number
                               (:next-player-number committed-room)}))))
        threads (mapv (fn [connection-token]
                        (doto (Thread.
                               ^Runnable
                               (reify Runnable
                                 (run [_]
                                   (.countDown ready)
                                   (try
                                     (when-not (.await start 2 TimeUnit/SECONDS)
                                       (throw (ex-info "start latch timed out" {})))
                                     (room/enqueue-room-message!
                                      room-agent
                                      {:type :join
                                       :connection-token connection-token})
                                     (catch Throwable error
                                       (swap! failures conj error))
                                     (finally
                                       (.countDown finished))))))
                          (.setDaemon true)))
                      request-tokens)]
    (try
      (reset! room-ref room-agent)
      (doseq [thread threads]
        (.start thread))
      (let [all-ready? (.await ready 2 TimeUnit/SECONDS)]
        (is all-ready? "all concurrent submitters should reach the start latch")
        (.countDown start))
      (is (.await finished 2 TimeUnit/SECONDS)
          "concurrent submitters should enqueue before the deadline")
      (is (empty? @failures) (pr-str @failures))
      (await-agent! room-agent)
      (let [captured (mapv (fn [_] (.poll deliveries))
                           (range request-count))
            effects (vec (mapcat :effects (remove nil? captured)))
            replies-by-token (group-by :connection-token effects)
            identity-effects (filter #(= :identity (get-in % [:message :type]))
                                    effects)
            accepted-tokens (set (map :connection-token identity-effects))
            admitted-members (vals (get-in @room-agent [:room :members]))]
        (is (every? some? captured)
            "each queued join should yield one captured delivery vector")
        (is (= request-count (count effects)))
        (let [committed-player-counts (mapv #(count (:world-player-ids %)) captured)]
          (is (= 1 (first committed-player-counts)))
          (is (= 4 (last committed-player-counts)))
          (is (every? #(<= 1 % 4) committed-player-counts))
          (is (every? (fn [[before after]] (<= before after (inc before)))
                      (partition 2 1 committed-player-counts))))
        (is (= request-count (count captured))
            "each callback should observe the committed world after that join")
        (is (every? #(= (:member-player-ids %) (:world-player-ids %)) captured)
            "every post-commit callback should see one world player per member")
        (is (every? #(= (inc (count (:members %)))
                        (:next-player-number %))
                    captured)
            "the committed identity counter should match admitted members")
        (doseq [[previous current] (partition 2 1 (cons {:members {}
                                                        :world-player-ids #{}
                                                        :next-player-number 1}
                                                       captured))]
          (let [[effect] (:effects current)
                connection-token (:connection-token effect)
                previous-player-ids (:world-player-ids previous)
                current-player-ids (:world-player-ids current)
                admitted-before? (contains? (:members previous) connection-token)
                room-full? (= :room-full (get-in effect [:message :type]))]
            (if (or admitted-before? room-full?)
              (is (= previous-player-ids current-player-ids)
                  "duplicate replies and capacity rejections must not spawn players")
              (is (= (inc (count previous-player-ids))
                     (count current-player-ids))
                  "a newly accepted member should add one world player"))))
        (is (= 4 (count admitted-members)))
        (is (= 5 (get-in @room-agent [:room :next-player-number])))
        (is (= 4 (count (get-in @room-agent [:room :world :players]))))
        (is (= (set (map :player-id admitted-members))
               (set (keys (get-in @room-agent [:room :world :players]))))
            "concurrent joins should commit exactly one world player per member")
        (is (= 4 (count accepted-tokens)))
        (is (= 4 (count (set (map :player-id admitted-members))))
            "accepted members should have distinct player IDs")
        (is (= 4 (count (set (map :color admitted-members))))
            "accepted members should have distinct colors")
        (doseq [[connection-token token-effects] replies-by-token]
          (let [messages (map :message token-effects)]
            (is (apply = messages)
                (str "duplicate submissions should retain one result for "
                     (pr-str connection-token)))))
        (is (every? #(contains? #{:identity :room-full}
                                (get-in % [:message :type]))
                    effects)))
      (finally
        (.countDown start)
        (reset! room-ref nil)))))

(deftest ability-intentions-are-kept-for-active-players-and-discarded-otherwise-test
  (let [joined (-> (:room (join-room room/initial-room :connection-a))
                   (update-in [:world :players :player-1] assoc :lifecycle :active :stocks 3)
                   match-helpers/running-room)
        requests (merge protocol/neutral-input
                        (zipmap protocol/ability-action-keys (repeat true))
                        {:horizontal 1})
        pending-after (fn [room]
                        (get-in (room/apply-room-message room (input-message :connection-a requests))
                                [:room :inputs-by-player :player-1]))]
    (is (= requests (pending-after joined))
        "an active running contender keeps every requested intention until a tick")
    (doseq [[label room] [["waiting lifecycle"
                           (update-in joined [:world :players :player-1] assoc :lifecycle :waiting :stocks 0)]
                          ["respawning lifecycle"
                           (assoc-in joined [:world :players :player-1 :lifecycle] :respawning)]
                          ["eliminated lifecycle"
                           (update-in joined [:world :players :player-1] assoc :lifecycle :eliminated :stocks 0)]
                          ["countdown phase" (assoc-in joined [:world :match :phase] :countdown)]
                          ["finished phase" (assoc-in joined [:world :match :phase] :finished)]]]
      (is (= (assoc protocol/neutral-input :horizontal 1) (pending-after room))
          (str label " discards one-shot intentions but keeps direction")))
    (let [accepted (:room (room/apply-room-message joined (input-message :connection-a requests)))
          ticked (:room (room/apply-room-message accepted {:type :tick}))]
      (is (= (assoc protocol/neutral-input :horizontal 1)
             (get-in ticked [:inputs-by-player :player-1]))
          "a tick consumes intentions exactly like jump, drop, and melee"))))

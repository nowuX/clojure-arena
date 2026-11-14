(ns clojure-arena.room-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room])
  (:import [java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit]))

(defn- join-room [state connection-token]
  (room/apply-room-message state
                           {:type :join
                            :connection-token connection-token}))

(defn- await-agent! [room-agent]
  (is (await-for 2000 room-agent)
      "room agent should finish its queued actions before the deadline")
  (is (nil? (agent-error room-agent))
      "room actions and post-commit delivery should not fail the agent"))

(deftest apply-room-message-is-pure-and-deterministic-test
  (let [initial room/initial-room
        first-result (join-room initial :connection-a)
        second-result (join-room initial :connection-a)]
    (is (= first-result second-result))
    (is (= room/initial-room initial))
    (is (identical? initial
                    (:room (room/apply-room-message initial {:type :unknown}))))
    (is (= {:room {:members {:connection-a {:player-id :player-1 :color :cyan}}
                   :next-player-number 2}
            :effects [{:connection-token :connection-a
                       :message {:type :identity
                                 :player-id :player-1
                                 :color :cyan}}]}
           first-result))
    (is (not-any? fn?
                  (tree-seq coll? seq first-result)))))

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
  (let [seeded-room {:members {:existing {:player-id :player-7 :color :cyan}}
                     :next-player-number 41}
        result (join-room seeded-room :new-connection)]
    (is (= {:members {:existing {:player-id :player-7 :color :cyan}
                      :new-connection {:player-id :player-41 :color :crimson}}
            :next-player-number 42}
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
                      (swap! callback-records conj
                             {:effects effects
                              :committed-member-count
                              (count (:members (:room (deref (deref room-ref)))))})))]
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
      (is (= [[{:connection-token :first
                :message {:type :identity :player-id :player-1 :color :cyan}}]
              [{:connection-token :second
                :message {:type :identity :player-id :player-2 :color :crimson}}]
              [{:connection-token :third
                :message {:type :identity :player-id :player-3 :color :amber}}]]
             (mapv :effects @callback-records)))
      (is (= {:room {:members {:first {:player-id :player-1 :color :cyan}
                               :second {:player-id :player-2 :color :crimson}
                               :third {:player-id :player-3 :color :amber}}
                     :next-player-number 4}
              :effects [{:connection-token :third
                         :message {:type :identity
                                   :player-id :player-3
                                   :color :amber}}]}
             @room-agent))
      (finally
        (reset! room-ref nil)))))

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
        room-agent (room/make-room #(.add deliveries %))
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
            effects (vec (mapcat identity (remove nil? captured)))
            replies-by-token (group-by :connection-token effects)
            identity-effects (filter #(= :identity (get-in % [:message :type]))
                                    effects)
            accepted-tokens (set (map :connection-token identity-effects))
            admitted-members (vals (get-in @room-agent [:room :members]))]
        (is (every? some? captured)
            "each queued join should yield one captured delivery vector")
        (is (= request-count (count effects)))
        (is (= 4 (count admitted-members)))
        (is (= 5 (get-in @room-agent [:room :next-player-number])))
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
        (.countDown start)))))

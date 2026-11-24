(ns clojure-arena.server-session-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]
            [clojure-arena.server :as server]
            [clojure-arena.ticker :as ticker]
            [clojure-arena.websocket-test :as ws]
            [org.httpkit.server :as http])
  (:import [java.net.http HttpClient WebSocket]
           [java.time Duration]
           [java.util.concurrent CompletableFuture CountDownLatch
            TimeUnit]))

(def ^:private timeout-ms 4000)
(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 3))
      (.build)))

(defn- test-host []
  (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1"))

(defn- await-future [^CompletableFuture future]
  (.get future timeout-ms TimeUnit/MILLISECONDS))

(defn- await-room! [room-agent]
  (is (await-for timeout-ms room-agent)
      "the room agent should finish processing queued messages")
  (is (nil? (agent-error room-agent))
      "room message processing should not fail"))

(defn- await-room-state! [room-agent predicate]
  (let [watch-key (Object.)
        changed (CountDownLatch. 1)
        deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (add-watch room-agent watch-key
               (fn [_ _ _ new-state]
                 (when (predicate new-state)
                   (.countDown changed))))
    (try
      (let [matched? (atom false)
            finished? (atom false)]
        (while (not @finished?)
          (cond
            (predicate @room-agent)
            (do (reset! matched? true)
                (reset! finished? true))

            (>= (System/nanoTime) deadline)
            (reset! finished? true)

            :else
            (.await changed (- deadline (System/nanoTime))
                    TimeUnit/NANOSECONDS)))
        @matched?)
      (finally
        (remove-watch room-agent watch-key)))))

(defn- start-runtime! []
  (let [captured (atom nil)
        original-make-handler server/make-handler
        make-handler (fn [public-dir]
                       (let [handler (original-make-handler public-dir)]
                         (reset! captured (meta handler))
                         handler))
        instance (with-redefs [server/make-handler make-handler
                               ticker/start! (constantly nil)]
                   (server/start! {:host (test-host) :port 0}))
        metadata @captured]
    {:server instance
     :port (http/server-port instance)
     :room-agent (::server/room-agent metadata)
     :connections (::server/connections metadata)}))

(defn- connect-with-cookie! [port cookie]
  (let [{:keys [events listener]} (#'ws/new-listener)
        builder (.header (.newWebSocketBuilder client) "Cookie" cookie)
        websocket (await-future
                   (.buildAsync builder
                                (#'ws/websocket-uri (test-host) port)
                                listener))]
    {:websocket websocket :events events}))

(defn- tracked-session! [clients port cookie]
  (let [connection (connect-with-cookie! port cookie)]
    (swap! clients conj connection)
    (#'ws/await-ready! (:events connection))
    (let [admission (#'ws/await-admission! (:events connection))]
      (assoc connection
             :admission admission
             :identity (when (= :identity (:type admission)) admission)))))

(defn- abort-clients! [clients]
  (doseq [{:keys [websocket]} @clients]
    (#'ws/abort-websocket! websocket)))

(defn- room-state [room-agent]
  (:room @room-agent))

(defn- member-token [room-state player-id]
  (some (fn [[token member]]
          (when (= player-id (:player-id member)) token))
        (:members room-state)))

(defn- send-input! [room-agent {:keys [websocket events]} input]
  (#'ws/send-text! websocket (pr-str (assoc input :type :input)) true)
  (#'ws/expect-reply! websocket events "{:type :ping}" {:type :pong})
  (await-room! room-agent))

(defn- tick-room! [room-agent]
  (room/enqueue-room-message! room-agent {:type :tick})
  (await-room! room-agent))

(defn- waiting-spawn-player [color]
  (assoc (room/make-spawn-player color) :lifecycle :waiting :stocks 0))

(deftest reload-creates-a-fresh-player-session-with-the-same-cookie-test
  (let [{:keys [server port room-agent connections]} (start-runtime!)
        clients (atom [])
        cookie "arena-session=shared-page"]
    (try
      (let [old (tracked-session! clients port cookie)
            old-id (get-in old [:identity :player-id])
            old-color (get-in old [:identity :color])
            old-token (member-token (room-state room-agent) old-id)
            old-input (assoc protocol/neutral-input :horizontal 1 :jump? true :drop? true :attack? true)
            old-player-changes {:x 295 :y 264 :vx -135 :vy 65
                                :knockback-vx 180 :knockback-vy -90
                                :facing :left :spawn-locked? false :air-jumps-left 0
                                :drop-through-platform-id :left
                                :melee-cooldown-left 0.17 :melee-strike-id 9
                                :melee-resolved-strike-id 8
                                :melee-target-ids #{:player-77}
                                :accumulated-percentage 40}]
        (await-room! room-agent)
        (is (= :player-1 old-id))
        (send-input! room-agent old old-input)
        (send room-agent
              (fn [envelope]
                (update-in envelope [:room :world :players old-id]
                           merge old-player-changes)))
        (await-room! room-agent)
        (is (= (assoc protocol/neutral-input :horizontal 1)
               (get-in (room-state room-agent) [:inputs-by-player old-id]))
            "a waiting session retains direction and drops one-shot actions")
        (is (= old-player-changes
               (select-keys (get-in (room-state room-agent)
                                    [:world :players old-id])
                            (keys old-player-changes)))
            "the first player should have changed physical and combat state before reload")

        (testing "a clean close removes the old player before a same-cookie reload joins"
          (#'ws/close-websocket! (:websocket old) (:events old))
          (is (await-room-state!
               room-agent
               #(not (contains? (get-in % [:room :world :players]) old-id)))
              "the clean close should commit the old session's leave")
          (is (not (contains? (:members (room-state room-agent)) old-token)))
          (is (not (contains? (:inputs-by-player (room-state room-agent)) old-id)))
          (is (not (contains? @connections old-token)))

          (let [replacement (tracked-session! clients port cookie)
                new-id (get-in replacement [:identity :player-id])
                new-token (member-token (room-state room-agent) new-id)]
            (await-room! room-agent)
            (is (= :player-2 new-id)
                "a reload should receive a new increasing player ID")
            (is (= old-color (get-in replacement [:identity :color]))
                "the released color should be available to the new session")
            (is (not= old-token new-token)
                "each WebSocket connection should have its own token")
            (is (= #{new-id}
                   (set (keys (get-in (room-state room-agent) [:world :players])))))
            (is (= (waiting-spawn-player old-color)
                   (get-in (room-state room-agent) [:world :players new-id]))
                "reload should build a fresh waiting player, not restore old state")
            (is (= protocol/neutral-input
                   (get-in (room-state room-agent) [:inputs-by-player new-id]))
                "held and pending inputs should not cross session boundaries")

            (tick-room! room-agent)
            (let [first-tick-player (get-in (room-state room-agent)
                                            [:world :players new-id])]
              (is (:spawn-locked? first-tick-player)
                  "the fresh waiting spawn keeps its factory lock")
              (is (== 160 (:y first-tick-player))
                  "a lone waiting player stays frozen at its spawn")
              (is (= :waiting (:lifecycle first-tick-player)))
              (is (= 0 (:stocks first-tick-player)))))))
      (finally
        (abort-clients! clients)
        (server/stop! server)))))

(deftest same-cookie-tabs-remain-distinct-and-respect-room-capacity-test
  (let [{:keys [server port room-agent]} (start-runtime!)
        clients (atom [])
        cookie "arena-session=shared-tabs"]
    (try
      (let [tabs (mapv (fn [_] (tracked-session! clients port cookie)) (range 4))
            old (first tabs)
            old-id (get-in old [:identity :player-id])
            old-token (member-token (room-state room-agent) old-id)
            ids (mapv #(get-in % [:identity :player-id]) tabs)
            room-full (tracked-session! clients port cookie)]
        (await-room! room-agent)
        (testing "tabs carrying the same cookie receive separate connections and players"
          (is (= [:player-1 :player-2 :player-3 :player-4] ids))
          (is (= 4 (count (set (map #(member-token (room-state room-agent) %) ids)))))
          (is (= (set ids)
                 (set (keys (get-in (room-state room-agent) [:world :players]))))))

        (testing "a same-cookie fifth connection is rejected while the old session stays open"
          (is (= {:type :room-full} (:admission room-full)))
          (#'ws/expect-reply! (:websocket old) (:events old)
                              "{:type :ping}" {:type :pong})
          (is (= 4 (count (:members (room-state room-agent)))))
          (is (= (set ids)
                 (set (keys (get-in (room-state room-agent) [:world :players]))))
              "a rejected reload must not evict any admitted tab")
          (is (contains? (:members (room-state room-agent)) old-token))
          (#'ws/close-websocket! (:websocket room-full) (:events room-full))
          (await-room! room-agent)
          (is (= (set ids)
                 (set (keys (get-in (room-state room-agent) [:world :players]))))
              "closing a rejected connection must not change admitted players"))

        (let [departing (last tabs)
              departing-id (get-in departing [:identity :player-id])]
          (#'ws/close-websocket! (:websocket departing) (:events departing))
          (is (await-room-state!
               room-agent
               #(not (contains? (get-in % [:room :world :players]) departing-id)))
              "closing one tab should release exactly its player slot")
          (let [replacement (tracked-session! clients port cookie)
                replacement-id (get-in replacement [:identity :player-id])
                replacement-token (member-token (room-state room-agent)
                                                replacement-id)]
            (await-room! room-agent)
            (is (= :player-5 replacement-id)
                "a clean retry should allocate a fresh ID without the rejected attempt consuming one")
            (is (= :green (get-in replacement [:identity :color]))
                "the tab's released color should be reused")
            (is (not= old-token replacement-token))
            (is (= #{:player-1 :player-2 :player-3 :player-5}
                   (set (keys (get-in (room-state room-agent) [:world :players])))))

            (testing "old-token input and cleanup remain scoped to the old tab"
              (#'ws/expect-reply! (:websocket old) (:events old)
                                  "{:type :ping}" {:type :pong})
              (send-input! room-agent old
                           (assoc protocol/neutral-input :horizontal -1 :attack? true))
              (is (= (assoc protocol/neutral-input :horizontal -1)
                     (get-in (room-state room-agent) [:inputs-by-player old-id])))
              (is (= protocol/neutral-input
                     (get-in (room-state room-agent)
                             [:inputs-by-player replacement-id]))
                  "the old live tab must not control the replacement")

              ;; Simulate the old token's queued leave arriving after the retry.
              (room/enqueue-room-message! room-agent
                                          {:type :leave :connection-token old-token})
              (is (await-room-state!
                   room-agent
                   #(not (contains? (get-in % [:room :world :players]) old-id))))
              (is (contains? (:members (room-state room-agent)) replacement-token))

              ;; The old channel can still deliver a delayed frame before its close callback.
              (send-input! room-agent old
                           (assoc protocol/neutral-input :horizontal 1 :jump? true :drop? true :attack? true))
              (is (= protocol/neutral-input
                     (get-in (room-state room-agent)
                             [:inputs-by-player replacement-id])))

              ;; Its eventual real close, plus any later stale room messages, are idempotent.
              (#'ws/close-websocket! (:websocket old) (:events old))
              (await-room! room-agent)
              (room/enqueue-room-message! room-agent
                                          {:type :input
                                           :connection-token old-token
                                           :input (assoc protocol/neutral-input :horizontal -1 :attack? true)})
              (room/enqueue-room-message! room-agent
                                          {:type :leave :connection-token old-token})
              (await-room! room-agent)
              (is (contains? (:members (room-state room-agent)) replacement-token)
                  "late old-token cleanup must leave the replacement admitted")
              (is (= protocol/neutral-input
                     (get-in (room-state room-agent)
                             [:inputs-by-player replacement-id]))
                  "late old-token input must not mutate the replacement")))))
      (finally
        (abort-clients! clients)
        (server/stop! server)))))

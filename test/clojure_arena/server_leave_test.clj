(ns clojure-arena.server-leave-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-arena.heartbeat :as heartbeat]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]
            [clojure-arena.server :as server]
            [clojure-arena.ticker :as ticker]
            [clojure-arena.websocket-test :as ws]
            [org.httpkit.server :as http])
  (:import [java.net ServerSocket]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CompletableFuture CountDownLatch
            LinkedBlockingQueue TimeUnit]))

(def ^:private timeout-ms 4000)
(def ^:private make-heartbeat-driver heartbeat/make-driver)

(defn- host []
  (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1"))

(defn- http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 3))
      (.build)))

(defn- await-future [^CompletableFuture future]
  (.get future timeout-ms TimeUnit/MILLISECONDS))

(defn- queue-take! [^LinkedBlockingQueue queue description]
  (or (.poll queue timeout-ms TimeUnit/MILLISECONDS)
      (throw (ex-info (str "Timed out waiting for " description)
                      {:description description}))))

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
      (loop []
        (if (predicate @room-agent)
          true
          (let [remaining (- deadline (System/nanoTime))]
            (if (pos? remaining)
              (do
                (.await changed remaining TimeUnit/NANOSECONDS)
                (recur))
              false))))
      (finally
        (remove-watch room-agent watch-key)))))

(defn- room-state [room-agent]
  (:room @room-agent))

(defn- start-captured-server! [driver-factory]
  (let [captured (atom nil)
        original-make-handler server/make-handler
        make-handler (fn [public-dir]
                       (let [handler (original-make-handler public-dir)]
                         (reset! captured (meta handler))
                         handler))
        original-driver heartbeat/make-driver
        make-driver (if driver-factory
                      driver-factory
                      original-driver)
        instance (with-redefs [server/make-handler make-handler
                               heartbeat/make-driver make-driver
                               ticker/start! (constantly nil)]
                   (server/start! {:host (host) :port 0}))]
    {:server instance
     :port (http/server-port instance)
     :handler-meta @captured
     :room-agent (::server/room-agent @captured)
     :connections (::server/connections @captured)
     :heartbeat (::server/heartbeat @captured)}))

(defn- tracked-connect! [connections port]
  (let [connection (#'ws/connect! (host) port)]
    (swap! connections conj connection)
    (#'ws/await-ready! (:events connection))
    (assoc connection :identity (#'ws/await-identity! (:events connection)))))

(defn- abort-all! [connections]
  (doseq [{:keys [websocket]} @connections]
    (#'ws/abort-websocket! websocket)))

(defn- member-token [room-state player-id]
  (some (fn [[token member]]
          (when (= player-id (:player-id member)) token))
        (:members room-state)))

(defn- next-snapshot! [events tick]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [remaining (max 0 (- deadline (System/nanoTime)))
            [kind text :as event] (.poll ^LinkedBlockingQueue events remaining
                                         TimeUnit/NANOSECONDS)]
        (when-not (= :text kind)
          (throw (ex-info "Expected a WebSocket snapshot before the deadline"
                          {:event event})))
        (let [message (protocol/decode text)]
          (if (and (= :snapshot (:type message)) (= tick (:tick message)))
            message
            (recur)))))))

(deftest abrupt-websocket-abort-releases-player-input-and-color
  (let [{:keys [server port room-agent connections] :as runtime}
        (start-captured-server! nil)
        clients (atom [])]
    (try
      (let [departing (tracked-connect! clients port)
            survivor (tracked-connect! clients port)
            departing-id (get-in departing [:identity :player-id])
            survivor-id (get-in survivor [:identity :player-id])
            input (assoc protocol/neutral-input :type :input :horizontal 1)]
        (await-room! room-agent)
        (#'ws/send-text! (:websocket departing) (pr-str input) true)
        (#'ws/expect-reply! (:websocket departing) (:events departing)
                            "{:type :ping}" {:type :pong})
        (await-room! room-agent)
        (let [old-token (member-token (room-state room-agent) departing-id)
              departing-color (get-in departing [:identity :color])]
          (is (= 2 (count (:members (room-state room-agent)))))
          (is (= 1 (get-in (room-state room-agent)
                           [:inputs-by-player departing-id :horizontal])))
          (#'ws/abort-websocket! (:websocket departing))
          (is (await-room-state!
               room-agent
               #(not (contains? (get-in % [:room :world :players]) departing-id)))
              "an abrupt socket abort should enqueue the player's leave")
          (is (not (contains? @connections old-token))
              "the closed channel should leave the connection registry immediately")
          (is (not (contains? (:inputs-by-player (room-state room-agent)) departing-id))
              "leaving should remove the player's retained input")
          (is (= #{survivor-id}
                 (set (keys (get-in (room-state room-agent) [:world :players])))))
          (let [replacement (tracked-connect! clients port)]
            (await-room! room-agent)
            (is (= :player-3 (get-in replacement [:identity :player-id])))
            (is (= departing-color (get-in replacement [:identity :color]))
                "a fresh socket should reuse the departed player's color")
            (is (= #{survivor-id :player-3}
                   (set (keys (get-in (room-state room-agent) [:world :players])))))
            (is (= 2 (count (:members (room-state room-agent)))))
            (is (= #{survivor-id :player-3}
                   (set (keys (:inputs-by-player (room-state room-agent)))))))))
      (finally
        (abort-all! clients)
        (server/stop! server)))))

(deftest clean-close-removes-departed-player-from-survivor-snapshot
  (let [{:keys [server port room-agent]} (start-captured-server! nil)
        clients (atom [])]
    (try
      (let [departing (tracked-connect! clients port)
            survivor (tracked-connect! clients port)
            departing-id (get-in departing [:identity :player-id])
            survivor-id (get-in survivor [:identity :player-id])]
        (await-room! room-agent)
        (#'ws/close-websocket! (:websocket departing) (:events departing))
        (is (await-room-state!
             room-agent
             #(not (contains? (get-in % [:room :world :players]) departing-id)))
            "a clean close should remove the departed member")
        (room/enqueue-room-message! room-agent {:type :tick})
        (await-room! room-agent)
        (let [snapshot (next-snapshot! (:events survivor) 1)]
          (is (= #{survivor-id} (set (keys (get-in snapshot [:world :players])))))
          (is (not (contains? (get-in snapshot [:world :players]) departing-id)))
          (is (= (get-in (room-state room-agent) [:world :players])
                 (get-in snapshot [:world :players])))))
      (finally
        (abort-all! clients)
        (server/stop! server)))))

(defn- ping-aware-connect! [host port]
  (let [client (http-client)
        events (LinkedBlockingQueue.)
        pings (LinkedBlockingQueue.)
        fragments (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onOpen [_ websocket]
                     (.request websocket 1))
                   (onText [_ websocket data last]
                     (.append fragments ^CharSequence data)
                     (when last
                       (.offer events [:text (.toString fragments)])
                       (.setLength fragments 0))
                     (.request websocket 1)
                     (CompletableFuture/completedFuture nil))
                   (onPing [_ websocket message]
                     (let [copy (.duplicate ^ByteBuffer message)
                           payload (byte-array (.remaining copy))]
                       (.get copy payload)
                       (.offer pings payload)
                       (.request websocket 1)
                       ;; The native Java listener receives the control frame and
                       ;; returns its pong through the same WebSocket connection.
                       (.sendPong websocket (ByteBuffer/wrap payload))))
                   (onClose [_ _ status reason]
                     (.offer events [:close status reason])
                     (CompletableFuture/completedFuture nil))
                   (onError [_ _ error]
                     (.offer events [:error error])))]
    {:websocket
     (await-future
      (.buildAsync (.newWebSocketBuilder client)
                   (java.net.URI. (str "ws://" host ":" port "/ws")) listener))
     :events events
     :pings pings}))

(defn- instrumented-driver [now probes pongs]
  (fn [probe!]
    (let [driver (make-heartbeat-driver
                  (fn [token payload]
                    (.offer ^LinkedBlockingQueue probes
                            [token (aclone ^bytes payload)])
                    (probe! token payload))
                  #(long @now))
          delegate-pong! (:pong! driver)]
      (assoc driver :pong!
             (fn [token payload]
               (delegate-pong! token payload)
               (.offer ^LinkedBlockingQueue pongs
                       [token (aclone ^bytes payload)]))))))

(deftest heartbeat-control-ping-and-native-client-pong-keep-session-alive
  (let [now (atom 0)
        probes (LinkedBlockingQueue.)
        pongs (LinkedBlockingQueue.)
        clients (atom [])
        runtime (start-captured-server! (instrumented-driver now probes pongs))
        {:keys [server port room-agent heartbeat]} runtime]
    (try
      (let [{:keys [websocket events pings] :as client}
            (ping-aware-connect! (host) port)]
        (swap! clients conj client)
        (#'ws/await-ready! events)
        (let [identity (#'ws/await-identity! events)
              token (member-token (room-state room-agent) (:player-id identity))]
          ((:pulse! heartbeat))
          (let [payload (queue-take! pings "a server heartbeat Ping frame")
                [pong-token pong-payload] (queue-take! pongs "the client's Pong frame")]
            (is (= token pong-token))
            (is (= (seq payload) (seq pong-payload)))
            (is (= token (first (queue-take! probes "the server Ping probe"))))
            (reset! now 10000000000)
            ((:pulse! heartbeat))
            (is (= token (first (queue-take! probes "the next heartbeat probe")))
                "a matching Pong should let the connection pass a later heartbeat pulse"))))
      (finally
        (abort-all! clients)
        (server/stop! server)))))

(deftest timed-out-socket-cannot-mutate-its-replacement
  (let [now (atom 0)
        probes (LinkedBlockingQueue.)
        pongs (LinkedBlockingQueue.)
        clients (atom [])
        runtime (start-captured-server! (instrumented-driver now probes pongs))
        {:keys [server port room-agent connections heartbeat]} runtime]
    (try
      (let [{:keys [websocket events pings] :as old}
            (ping-aware-connect! (host) port)]
        (swap! clients conj old)
        (#'ws/await-ready! events)
        (let [old-identity (#'ws/await-identity! events)
              old-id (:player-id old-identity)
              old-color (:color old-identity)
              old-token (member-token (room-state room-agent) old-id)]
          ((:pulse! heartbeat))
          (let [old-payload (queue-take! pings "the old session's heartbeat Ping")]
            (queue-take! pongs "the old session's matching Pong")
            (is (= old-token (first (queue-take! probes "the old session's Ping probe"))))
            (reset! now (* heartbeat/timeout-ms 1000000))
            ((:pulse! heartbeat))
            (is (= {:type :error :code :connection-timeout}
                   (protocol/decode (#'ws/await-text! events))))
            (is (await-room-state!
                 room-agent
                 #(not (contains? (get-in % [:room :world :players]) old-id)))
                "expiration should release the old player immediately")
            (is (not (contains? @connections old-token))
                "expiration should remove the old channel before the timeout reply")
            (let [replacement (#'ws/connect! (host) port)]
              (swap! clients conj replacement)
              (#'ws/await-ready! (:events replacement))
              (let [replacement-identity (#'ws/await-identity! (:events replacement))
                    replacement-id (:player-id replacement-identity)
                    replacement-token (member-token (room-state room-agent)
                                                    replacement-id)]
                (is (= :player-2 replacement-id))
                (is (= old-color (:color replacement-identity)))
                (await-room! room-agent)
                (is (not= old-token replacement-token))

                ;; Every callback from the expired channel is deliberately late:
                ;; the token's leave has already committed and a new owner has
                ;; already taken its released color.
                (#'ws/send-text! websocket
                                 (pr-str (assoc protocol/neutral-input
                                                :type :input :horizontal -1))
                                 true)
                (await-future (.sendPong ^WebSocket websocket
                                         (ByteBuffer/wrap old-payload)))
                (await-future (.sendClose ^WebSocket websocket
                                          WebSocket/NORMAL_CLOSURE "expired"))
                (is (= :close (first (#'ws/next-control-event! events timeout-ms)))
                    "the timed-out WebSocket can still finish its close handshake")
                (await-room! room-agent)
                (is (contains? (:members (room-state room-agent)) replacement-token))
                (is (= #{replacement-id}
                       (set (keys (get-in (room-state room-agent)
                                          [:world :players])))))
                (is (= protocol/neutral-input
                       (get-in (room-state room-agent)
                                [:inputs-by-player replacement-id]))
                    "late input from the expired session must not reach the new member")
                (is (not (contains? @connections old-token)))
                (is (contains? @connections replacement-token)
                    "the replacement channel should remain registered")
                (reset! now (+ (* heartbeat/timeout-ms 1000000) 5000000000))
                ((:pulse! heartbeat))
                (is (= replacement-token
                       (first (queue-take! probes "a probe for the replacement")))
                    "a stale Pong must not replace or refresh the new session's heartbeat"))))))
      (finally
        (abort-all! clients)
        (server/stop! server)))))

(deftest close-callback-before-open-cannot-admit-a-retired-channel
  (let [room-agent (room/make-room (constantly nil))
        connections (atom {})
        callbacks (atom nil)
        channel (Object.)]
    (with-redefs [http/as-channel (fn [_ options]
                                   (reset! callbacks options)
                                   channel)]
      (server/websocket-response {:websocket? true} room-agent connections)
      ((:on-close @callbacks) channel 1000)
      ((:on-open @callbacks) channel)
      ((:on-receive @callbacks) channel "{:type :ping}")
      ((:on-close @callbacks) channel 1000))
    (await-room! room-agent)
    (is (empty? @connections))
    (is (= room/initial-room (:room @room-agent))
        "an open callback arriving after close must not enqueue a join")))

(defn- unused-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest heartbeat-startup-failure-stops-ticker-and-releases-listening-port
  (let [port (unused-port)
        ticker-handle (atom nil)
        original-ticker-start ticker/start!
        failure (try
                  (with-redefs [ticker/start!
                                (fn [room-agent]
                                  (let [handle (original-ticker-start room-agent)]
                                    (reset! ticker-handle handle)
                                    handle))
                                heartbeat/start!
                                (fn [_]
                                  (throw (ex-info "simulated heartbeat startup failure" {})))]
                    (server/start! {:host (host) :port port}))
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
    (try
      (is (= "simulated heartbeat startup failure" (.getMessage failure)))
      (is (some? @ticker-handle) "the ticker should have started before failure")
      (is (.isShutdown (:executor @ticker-handle))
          "server startup failure should stop the already-started ticker executor")
      (is (.awaitTermination (:executor @ticker-handle) timeout-ms TimeUnit/MILLISECONDS)
          "the ticker executor should terminate before startup returns")
      (let [instance (server/start! {:host (host) :port port})]
        (try
          (is (= port (http/server-port instance))
              "failed startup should release its bound port")
          (finally
            (server/stop! instance))))
      (finally
        (when-let [handle @ticker-handle]
          (ticker/stop! handle))))))

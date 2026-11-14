(ns clojure-arena.websocket-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.room :as room]
            [clojure-arena.server :as server]
            [clojure-arena.ticker :as ticker]
            [org.httpkit.server :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
            WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CompletableFuture LinkedBlockingQueue
            TimeUnit]))

(def ^:private timeout-ms 4000)

(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 3))
      (.build)))

(defn- await-future [^CompletableFuture future]
  (.get future timeout-ms TimeUnit/MILLISECONDS))

(defn- test-host []
  (or (System/getenv "CLOJURE_ARENA_TEST_HOST") "127.0.0.1"))

(defn- uri-host [host]
  (if (and (str/includes? host ":")
           (not (str/starts-with? host "[")))
    (str "[" host "]")
    host))

(defn- websocket-uri [host port]
  (URI. (str "ws://" (uri-host host) ":" port "/ws")))

(defn- new-listener []
  (let [events (LinkedBlockingQueue.)
        fragments (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onOpen [_ websocket]
                     (.request websocket 1))
                   (onText [_ websocket data last]
                     (.append ^StringBuilder fragments ^CharSequence data)
                     (when last
                       (.offer events [:text (.toString fragments)])
                       (.setLength ^StringBuilder fragments 0))
                     (.request websocket 1)
                     (CompletableFuture/completedFuture nil))
                   (onBinary [_ websocket data _]
                     (.offer events [:binary (.remaining ^ByteBuffer data)])
                     (.request websocket 1)
                     (CompletableFuture/completedFuture nil))
                   (onClose [_ _ status reason]
                     (.offer events [:close status reason])
                     (CompletableFuture/completedFuture nil))
                   (onError [_ _ error]
                     (.offer events [:error error])))]
    {:events events :listener listener}))

(defn- connect! [host port]
  (let [{:keys [events listener]} (new-listener)
        websocket (await-future (.buildAsync (.newWebSocketBuilder client)
                                             (websocket-uri host port)
                                             listener))]
    {:websocket websocket :events events}))

(defn- connect-tracked! [connections host port]
  (let [connection (connect! host port)]
    (swap! connections conj connection)
    connection))

(defn- connect-async! [host port]
  (let [{:keys [events listener]} (new-listener)]
    {:future (.buildAsync (.newWebSocketBuilder client)
                          (websocket-uri host port)
                          listener)
     :events events}))

(defn- await-connect! [{:keys [^CompletableFuture future events]}]
  {:websocket (await-future future) :events events})

(defn- next-event! [events]
  (or (.poll ^LinkedBlockingQueue events timeout-ms TimeUnit/MILLISECONDS)
      (throw (ex-info "Timed out waiting for a WebSocket event"
                      {:timeout-ms timeout-ms}))))

(defn- await-text! [events]
  (let [[kind value :as event] (next-event! events)]
    (if (= :text kind)
      value
      (throw (ex-info "Expected a WebSocket text message"
                      {:event event})))))

(defn- await-server-message! [events]
  (let [message (protocol/decode (await-text! events))]
    (is (protocol/server-message? message)
        (str "reply failed server-message? validation: " (pr-str message)))
    message))

(defn- await-ready! [events]
  (let [message (await-server-message! events)]
    (is (= {:type :ready} message)
        (str "expected the ready message first, got " (pr-str message)))
    message))

(defn- await-admission! [events]
  (let [message (await-server-message! events)]
    (is (contains? #{:identity :room-full} (:type message))
        (str "expected an admission result after ready, got " (pr-str message)))
    message))

(defn- assert-identity! [message]
  (is (= #{:type :player-id :color} (set (keys message)))
      (str "identity must have exactly the documented keys: " (pr-str message)))
  (is (= :identity (:type message)))
  (is (keyword? (:player-id message))
      (str "player-id must be a keyword: " (pr-str message)))
  (is (and (keyword? (:player-id message))
           (re-matches #"player-[1-9][0-9]*" (name (:player-id message))))
      (str "player-id must be a :player-N keyword: " (pr-str message)))
  (is (some #{(:color message)} protocol/player-colors)
      (str "unknown player color: " (pr-str message)))
  (is (protocol/server-message? message)
      (str "identity failed server-message? validation: " (pr-str message)))
  message)

(defn- await-identity! [events]
  (assert-identity! (await-admission! events)))

(defn- assert-no-event! [events context]
  (let [event (.poll ^LinkedBlockingQueue events 100 TimeUnit/MILLISECONDS)]
    (is (nil? event)
        (str "unexpected extra WebSocket event " (pr-str event) " " context))))

(defn- send-text! [websocket text last-fragment?]
  (await-future (.sendText ^WebSocket websocket text last-fragment?)))

(defn- expect-reply! [websocket events text expected]
  (send-text! websocket text true)
  (let [reply (await-server-message! events)]
    (is (= expected reply)
        (str "unexpected reply to " (pr-str text)))
    reply))

(defn- await-room! [room-agent]
  (is (await-for timeout-ms room-agent)
      "room agent should finish processing the frame before inspection")
  (is (nil? (agent-error room-agent))
      "room input processing should not fail the room agent"))

(defn- send-input-frame! [websocket events room-agent payload]
  (send-text! websocket (pr-str (assoc payload :type :input)) true)
  ;; The pong confirms the next frame handler ran; await-for then waits for the
  ;; asynchronous room action queued by the preceding input frame.
  (expect-reply! websocket events "{:type :ping}" {:type :pong})
  (await-room! room-agent)
  (assert-no-event! events "valid input frames must not receive a reply"))

(defn- close-websocket! [websocket events]
  (await-future (.sendClose ^WebSocket websocket
                            WebSocket/NORMAL_CLOSURE
                            "test complete"))
  (let [[kind status reason :as event] (next-event! events)]
    (is (= :close kind) (str "expected close event, got " (pr-str event)))
    (is (= WebSocket/NORMAL_CLOSURE status)
        (str "unexpected close reason: " reason))
    status))

(defn- abort-websocket! [websocket]
  (when websocket
    (.abort ^WebSocket websocket)))

(defn- start-test-server! [host port]
  (server/start! {:host host :port port}))

(defn- http-get [host port path]
  (let [request (-> (HttpRequest/newBuilder
                     (URI. (str "http://" (uri-host host) ":" port path)))
                    (.timeout (Duration/ofSeconds 3))
                    (.GET)
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(deftest websocket-upgrade-required-test
  (let [host (test-host)
        instance (start-test-server! host 0)
        port (http/server-port instance)]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (testing "a plain GET to /ws requires a WebSocket upgrade"
        (let [response (http-get host port "/ws")]
          (is (= 426 (.statusCode response)))
          (is (= "websocket"
                 (-> response .headers (.firstValue "Upgrade") (.orElse ""))))
          (is (= "WebSocket connection required\n" (.body response)))))
      (finally
        (server/stop! instance)))))

(deftest websocket-protocol-roundtrip-test
  (let [host (test-host)
        instance (start-test-server! host 0)
        port (http/server-port instance)]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (let [{websocket :websocket events :events} (connect! host port)]
        (try
          (testing "open sends ready, then one automatic identity"
            (await-ready! events)
            (is (= {:type :identity :player-id :player-1 :color :cyan}
                   (await-identity! events))))
          (testing "repeated pings each receive a pong"
            (dotimes [_ 3]
              (expect-reply! websocket events "{:type :ping}" {:type :pong}))
            (assert-no-event! events "pings must not allocate another identity"))
          (testing "fragmented text is assembled into one ping"
            (send-text! websocket "{:type " false)
            (send-text! websocket ":ping}" true)
            (let [reply (await-server-message! events)]
              (is (= {:type :pong} reply))
              (assert-no-event! events "fragmented ping")))
          (testing "malformed EDN reports an error and a later ping still works"
            (expect-reply! websocket events "{"
                           {:type :error :code :invalid-edn})
            (expect-reply! websocket events "{:type :ping}" {:type :pong}))
          (testing "internal actions, forged identities, and extra ping fields are rejected"
            (doseq [action [:join :tick :leave]]
              (expect-reply! websocket events
                             (pr-str {:type action})
                             {:type :error :code :unsupported-type}))
            (expect-reply! websocket events
                           "{:type :ping :player-id :player-99 :color :green}"
                           {:type :error :code :invalid-message})
            (expect-reply! websocket events
                           "{:type :identity :player-id :player-99 :color :green}"
                           {:type :error :code :unsupported-type})
            (expect-reply! websocket events
                           "{:type :join :player-id :player-99 :color :green}"
                           {:type :error :code :unsupported-type})
            (await-future
             (.sendBinary ^WebSocket websocket
                          (ByteBuffer/wrap (.getBytes "{:type :ping}"))
                          true))
            (let [reply (await-server-message! events)]
              (is (= {:type :error :code :invalid-message} reply))
              (assert-no-event! events "invalid wire messages")))
          (testing "a later ping still works after rejected wire messages"
            (expect-reply! websocket events "{:type :ping}" {:type :pong}))
          (testing "a normal close is acknowledged and a new connection works"
            (close-websocket! websocket events)
            (let [{reconnected :websocket reconnect-events :events}
                  (connect! host port)]
              (try
                (await-ready! reconnect-events)
                (is (= {:type :identity :player-id :player-2 :color :crimson}
                       (await-identity! reconnect-events)))
                (expect-reply! reconnected reconnect-events
                               "{:type :ping}" {:type :pong})
                (close-websocket! reconnected reconnect-events)
                (finally
                  (abort-websocket! reconnected)))))
          (finally
            (abort-websocket! websocket))))
      (finally
        (server/stop! instance)))))

(deftest websocket-inputs-are-owned-validated-and-queued-test
  (let [host (test-host)
        captured-room (atom nil)
        make-room room/make-room
        instance (with-redefs [room/make-room
                               (fn [deliver-effects!]
                                 (let [room-agent (make-room deliver-effects!)]
                                   (reset! captured-room room-agent)
                                   room-agent))
                               ;; Inspect pending input without automatic consumption.
                               ticker/start! (constantly nil)]
                   (start-test-server! host 0))
        port (http/server-port instance)
        connections (atom [])]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (is (some? @captured-room)
          "server startup should create the captured room agent")
      (let [room-agent @captured-room
            first-connection (connect-tracked! connections host port)
            second-connection (connect-tracked! connections host port)
            first-websocket (:websocket first-connection)
            first-events (:events first-connection)
            second-websocket (:websocket second-connection)
            second-events (:events second-connection)]
        (await-ready! first-events)
        (let [first-identity (await-identity! first-events)]
          (await-ready! second-events)
          (let [second-identity (await-identity! second-events)
                first-player (:player-id first-identity)
                second-player (:player-id second-identity)
                neutral-inputs {first-player protocol/neutral-input
                                second-player protocol/neutral-input}]
            (await-room! room-agent)
            (is (= #{:player-1 :player-2} #{first-player second-player})
                "two real sockets should own distinct admitted players")
            (is (= neutral-inputs
                   (get-in @room-agent [:room :inputs-by-player])))

            (testing "forged and malformed input fields return errors without mutation"
              (doseq [message [(assoc (assoc protocol/neutral-input :type :input)
                                      :player-id second-player)
                               (assoc (assoc protocol/neutral-input :type :input)
                                      :connection-token :forged)
                               (assoc (assoc protocol/neutral-input :type :input)
                                      :position [999 999])
                               (assoc (assoc protocol/neutral-input :type :input)
                                      :velocity [999 999])
                               (assoc (assoc protocol/neutral-input :type :input)
                                      :horizontal 2)
                               (dissoc (assoc protocol/neutral-input :type :input)
                                       :attack?)]]
                (expect-reply! first-websocket first-events (pr-str message)
                               {:type :error :code :invalid-message}))
              (expect-reply! first-websocket first-events "{"
                             {:type :error :code :invalid-edn})
              (is (= neutral-inputs
                     (get-in @room-agent [:room :inputs-by-player]))
                  "invalid client payloads must not mutate member inputs"))

            (testing "a short action tap survives a following neutral frame"
              (send-input-frame! first-websocket first-events room-agent
                                 {:horizontal -1
                                  :jump? true
                                  :drop? false
                                  :attack? false})
              (is (= {:horizontal -1 :jump? true :drop? false :attack? false}
                     (get-in @room-agent [:room :inputs-by-player first-player])))
              (send-input-frame! first-websocket first-events room-agent
                                 protocol/neutral-input)
              (is (= {:horizontal 0 :jump? true :drop? false :attack? false}
                     (get-in @room-agent [:room :inputs-by-player first-player]))
                  "neutral input should clear direction but retain the pending jump")
              (is (= protocol/neutral-input
                     (get-in @room-agent [:room :inputs-by-player second-player]))))

            (testing "a valid frame after errors is accepted for the other owner"
              (send-input-frame! second-websocket second-events room-agent
                                 {:horizontal 1
                                  :jump? false
                                  :drop? true
                                  :attack? true})
              (is (= {:horizontal 1 :jump? false :drop? true :attack? true}
                     (get-in @room-agent [:room :inputs-by-player second-player])))
              (is (= {:horizontal 0 :jump? true :drop? false :attack? false}
                     (get-in @room-agent [:room :inputs-by-player first-player])))))))
      (finally
        (doseq [{:keys [websocket]} @connections]
          (abort-websocket! websocket))
        (server/stop! instance)))))

(deftest websocket-stop-and-rebind-test
  (let [host (test-host)
        first-server (start-test-server! host 0)
        port (http/server-port first-server)]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (let [{websocket :websocket events :events} (connect! host port)]
        (try
          (await-ready! events)
          (is (= {:type :identity :player-id :player-1 :color :cyan}
                 (await-identity! events)))
          (close-websocket! websocket events)
          (finally
            (abort-websocket! websocket))))
      (let [{websocket :websocket events :events} (connect! host port)]
        (try
          (await-ready! events)
          (is (= {:type :identity :player-id :player-2 :color :crimson}
                 (await-identity! events))
              "closing a socket must not release its point-33 reservation")
          (close-websocket! websocket events)
          (finally
            (abort-websocket! websocket))))
      (finally
        (server/stop! first-server)))
    (let [second-server (start-test-server! host port)]
      (try
        (is (= port (http/server-port second-server))
            "the stopped WebSocket server's port should be reusable")
        (let [{websocket :websocket events :events} (connect! host port)]
          (try
            (await-ready! events)
            (is (= {:type :identity :player-id :player-1 :color :cyan}
                   (await-identity! events)))
            (expect-reply! websocket events "{:type :ping}" {:type :pong})
            (close-websocket! websocket events)
            (finally
              (abort-websocket! websocket))))
        (finally
          (server/stop! second-server))))))

(defn- player-number [identity]
  (Long/parseLong (subs (name (:player-id identity)) 7)))

(defn- cleanup-async-connections! [pending]
  (doseq [{:keys [^CompletableFuture future]} pending]
    (when (.isDone future)
      (try
        (abort-websocket! (.getNow future nil))
        (catch Exception _ nil)))
    (.cancel future true)))

(deftest websocket-simultaneous-admission-capacity-test
  (let [host (test-host)
        instance (start-test-server! host 0)
        port (http/server-port instance)
        pending (vec (repeatedly 9 #(connect-async! host port)))]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (let [connections (mapv await-connect! pending)
            admissions (mapv (fn [{:keys [events]}]
                              (await-ready! events)
                              (await-admission! events))
                            connections)
            identities (filterv #(= :identity (:type %)) admissions)
            full (filterv #(= :room-full (:type %)) admissions)
            expected-ids (set (map #(keyword (str "player-" %)) (range 1 5)))]
        (testing "simultaneous opens reserve exactly four unique identities"
          (is (= 4 (count identities)))
          (doseq [identity identities]
            (assert-identity! identity))
          (is (= expected-ids (set (map :player-id identities))))
          (is (= 4 (count (set (map :player-id identities)))))
          (is (= (vec protocol/player-colors)
                 (mapv :color (sort-by player-number identities))))
          (is (= 5 (count full)))
          (is (every? #(= {:type :room-full} %) full))
          (is (every? protocol/server-message? full)))
        (testing "admitted and full sockets remain usable, and pings never allocate"
          (doseq [[{:keys [websocket events]} admission]
                  (map vector connections admissions)]
            (is (contains? #{:identity :room-full} (:type admission)))
            (dotimes [_ 2]
              (expect-reply! websocket events "{:type :ping}" {:type :pong}))
            (assert-no-event! events "ping must not cause another identity or a close")))
        (testing "wire clients cannot invoke reserved room operations or forge identities"
          (let [index (first (keep-indexed (fn [index admission]
                                             (when (= :identity (:type admission))
                                               index))
                                           admissions))]
            (when (some? index)
              (let [{:keys [websocket events]} (nth connections index)]
                (doseq [[text expected] [["{:type :join}" {:type :error
                                                              :code :unsupported-type}]
                                         ["{:type :tick}" {:type :error
                                                             :code :unsupported-type}]
                                         ["{:type :leave}" {:type :error
                                                              :code :unsupported-type}]
                                         ["{:type :ping :player-id :player-99 :color :green}"
                                          {:type :error :code :invalid-message}]
                                         ["{:type :identity :player-id :player-99 :color :green}"
                                          {:type :error :code :unsupported-type}]
                                         ["{:type :join :player-id :player-99 :color :green}"
                                          {:type :error :code :unsupported-type}]]]
                  (expect-reply! websocket events text expected))
                (expect-reply! websocket events "{:type :ping}" {:type :pong})
                (assert-no-event! events "forged input must not add an identity"))))))
      (finally
        (cleanup-async-connections! pending)
        (server/stop! instance)))))

(deftest websocket-server-room-isolation-test
  (let [host (test-host)
        first-server (start-test-server! host 0)
        second-server (start-test-server! host 0)
        first-port (http/server-port first-server)
        second-port (http/server-port second-server)
        connections (atom [])]
    (try
      (is (pos? first-port))
      (is (pos? second-port))
      (let [first-a (connect-tracked! connections host first-port)
            second-a (connect-tracked! connections host first-port)
            first-b (connect-tracked! connections host second-port)
            second-b (connect-tracked! connections host second-port)]
        (doseq [[connection expected] [[first-a {:type :identity
                                                 :player-id :player-1 :color :cyan}]
                                       [second-a {:type :identity
                                                  :player-id :player-2 :color :crimson}]
                                       [first-b {:type :identity
                                                 :player-id :player-1 :color :cyan}]
                                       [second-b {:type :identity
                                                  :player-id :player-2 :color :crimson}]]]
          (let [{:keys [events]} connection]
            (await-ready! events)
            (is (= expected (await-identity! events))))))
      (finally
        (doseq [{:keys [websocket]} @connections]
          (abort-websocket! websocket))
        (server/stop! second-server)
        (server/stop! first-server)))))

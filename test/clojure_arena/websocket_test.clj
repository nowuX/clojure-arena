(ns clojure-arena.websocket-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.server :as server]
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

(defn- send-text! [websocket text last-fragment?]
  (await-future (.sendText ^WebSocket websocket text last-fragment?)))

(defn- expect-reply! [websocket events text expected]
  (send-text! websocket text true)
  (let [reply (protocol/decode (await-text! events))]
    (is (= expected reply)
        (str "unexpected reply to " (pr-str text)))
    (is (protocol/server-message? reply)
        (str "reply failed server-message? validation: " (pr-str reply)))
    reply))

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
          (testing "open sends the ready message"
            (let [welcome (protocol/decode (await-text! events))]
              (is (= {:type :ready} welcome))
              (is (protocol/server-message? welcome))))
          (testing "repeated pings each receive a pong"
            (dotimes [_ 3]
              (expect-reply! websocket events "{:type :ping}" {:type :pong})))
          (testing "fragmented text is assembled into one ping"
            (send-text! websocket "{:type " false)
            (send-text! websocket ":ping}" true)
            (let [reply (protocol/decode (await-text! events))]
              (is (= {:type :pong} reply))
              (is (protocol/server-message? reply))))
          (testing "malformed EDN reports an error and a later ping still works"
            (expect-reply! websocket events "{"
                           {:type :error :code :invalid-edn})
            (expect-reply! websocket events "{:type :ping}" {:type :pong}))
          (testing "internal actions and binary messages are rejected"
            (doseq [action [:join :tick :leave]]
              (expect-reply! websocket events
                             (pr-str {:type action})
                             {:type :error :code :unsupported-type}))
            (await-future
             (.sendBinary ^WebSocket websocket
                          (ByteBuffer/wrap (.getBytes "{:type :ping}"))
                          true))
            (let [reply (protocol/decode (await-text! events))]
              (is (= {:type :error :code :invalid-message} reply))
              (is (protocol/server-message? reply))))
          (testing "a normal close is acknowledged and a new connection works"
            (close-websocket! websocket events)
            (let [{reconnected :websocket reconnect-events :events}
                  (connect! host port)]
              (try
                (is (= {:type :ready}
                       (protocol/decode (await-text! reconnect-events))))
                (expect-reply! reconnected reconnect-events
                               "{:type :ping}" {:type :pong})
                (close-websocket! reconnected reconnect-events)
                (finally
                  (abort-websocket! reconnected)))))
          (finally
            (abort-websocket! websocket))))
      (finally
        (server/stop! instance)))))

(deftest websocket-stop-and-rebind-test
  (let [host (test-host)
        first-server (start-test-server! host 0)
        port (http/server-port first-server)]
    (try
      (is (pos? port) "port 0 should bind an ephemeral port")
      (let [{websocket :websocket events :events} (connect! host port)]
        (try
          (is (= {:type :ready} (protocol/decode (await-text! events))))
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
            (is (= {:type :ready} (protocol/decode (await-text! events))))
            (expect-reply! websocket events "{:type :ping}" {:type :pong})
            (close-websocket! websocket events)
            (finally
              (abort-websocket! websocket))))
        (finally
          (server/stop! second-server))))))

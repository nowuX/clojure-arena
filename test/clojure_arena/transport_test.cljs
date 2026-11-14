(ns clojure-arena.transport-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.transport :as transport]))

(def ^:private sockets (atom []))
(def ^:private status-element (atom nil))

(defn- fake-websocket [url]
  (let [socket #js {:url url :readyState 0 :sent #js [] :closeCount 0}]
    (set! (.-send socket) (fn [message] (.push (aget socket "sent") message)))
    (set! (.-close socket)
          (fn []
            (aset socket "closeCount" (inc (aget socket "closeCount")))
            (set! (.-readyState socket) 2)))
    (swap! sockets conj socket)
    socket))

(defn- restore-global! [property descriptor]
  (if descriptor
    (.defineProperty js/Object js/globalThis property descriptor)
    (js-delete js/globalThis property)))

(defn- transport-fixture [test-fn]
  (let [old-document (.getOwnPropertyDescriptor js/Object js/globalThis "document")
        old-websocket (.getOwnPropertyDescriptor js/Object js/globalThis "WebSocket")
        old-state @transport/connection-state
        element #js {:textContent ""}]
    (reset! sockets [])
    (reset! status-element element)
    (set! (.-document js/globalThis)
          #js {:getElementById (fn [id] (when (= id "connection-status") element))})
    (set! (.-WebSocket js/globalThis) fake-websocket)
    (try
      (test-fn)
      (finally
        (transport/stop!)
        (reset! transport/connection-state old-state)
        (reset! status-element nil)
        (restore-global! "document" old-document)
        (restore-global! "WebSocket" old-websocket)))))

(use-fixtures :each transport-fixture)

(defn- open! [socket]
  (set! (.-readyState socket) 1)
  ((.-onopen socket) #js {}))

(defn- receive! [socket text]
  ((.-onmessage socket) #js {:data text}))

(deftest websocket-address-follows-page-or-explicit-game-server
  (doseq [[page expected]
          [["http://100.109.130.32:8082/" "ws://100.109.130.32:8082/ws"]
           ["https://arena.example/game?x=1#arena" "wss://arena.example/ws"]
           ["http://[::1]:8082/" "ws://[::1]:8082/ws"]
           ["http://100.109.130.32:8081/?server=http%3A%2F%2F100.109.130.32%3A8082"
            "ws://100.109.130.32:8082/ws"]
           ["http://arena.example/?server=https://game.example:8443/ignored?x=1"
            "wss://game.example:8443/ws"]
           ["http://arena.example:8082/?server=" "ws://arena.example:8082/ws"]]]
    (is (= expected (transport/websocket-url page))))
  (doseq [page ["not a URL" "file:///game/index.html"
                "http://arena.example/?server=ws://game.example"
                "http://arena.example/?server=/relative"]]
    (is (thrown? js/Error (transport/websocket-url page)))))

(deftest opening-and-edn-exchange-confirm-the-connection
  (transport/start! "http://arena.example:8082/")
  (let [socket @transport/connection]
    (is (= :connecting (:status @transport/connection-state)))
    (is (= "ws://arena.example:8082/ws" (.-url socket)))
    (is (empty? (array-seq (aget socket "sent"))))
    (open! socket)
    (is (= :open (:status @transport/connection-state)))
    (is (= [{:type :ping}]
           (mapv protocol/decode (array-seq (aget socket "sent")))))
    (receive! socket "{:type :ready}")
    (is (= :open (:status @transport/connection-state)))
    (receive! socket "{:type :pong}")
    (is (= :ready (:status @transport/connection-state)))
    (is (= {:type :pong} (:message @transport/connection-state)))
    (is (= "Comunicación con el servidor verificada."
           (.-textContent @status-element)))))

(deftest invalid-server-frames-and-errors-remain-transport-state
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (doseq [frame ["" "{" "{:type :pong} nil" "nil" "[]"
                   "{:type :snapshot :players {}}" "{:type :pong :extra true}"
                   "{:type :error :code :unknown}" #js {}]]
      (receive! socket frame)
      (is (= :error (:status @transport/connection-state)))
      (is (identical? socket @transport/connection))
      (receive! socket "{:type :pong}")
      (is (= :ready (:status @transport/connection-state))))
    (receive! socket "{:type :error :code :invalid-message}")
    (is (= {:type :error :code :invalid-message}
           (:message @transport/connection-state)))
    (is (= :error (:status @transport/connection-state)))
    (is (= 1 (alength (aget socket "sent"))))))

(deftest identity-survives-the-ping-exchange-in-either-arrival-order
  (doseq [identity-first? [true false]]
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection
          identity {:player-id :player-41 :color :green}
          message (pr-str (assoc identity :type :identity))]
      (open! socket)
      (receive! socket "{:type :ready}")
      (if identity-first?
        (do (receive! socket message)
            (receive! socket "{:type :pong}"))
        (do (receive! socket "{:type :pong}")
            (receive! socket message)))
      (is (= identity (:identity @transport/connection-state)))
      (is (= :accepted (:admission @transport/connection-state)))
      (is (= :ready (:status @transport/connection-state)))
      (is (= "Comunicación con el servidor verificada. ID: player-41 · green."
             (.-textContent @status-element)))
      (receive! socket message)
      (is (= identity (:identity @transport/connection-state)))
      (is (= 1 (alength (aget socket "sent")))))))

(deftest invalid-identity-cannot-replace-the-assigned-identity
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection
        identity {:player-id :player-3 :color :amber}]
    (open! socket)
    (receive! socket (pr-str (assoc identity :type :identity)))
    (doseq [message [{:type :identity :player-id :player-4 :color :orange}
                     {:type :identity :player-id "player-4" :color :green}
                     {:type :identity :player-id :player-4}
                     {:type :identity :player-id :player-4 :color :green :extra 1}]]
      (receive! socket (pr-str message))
      (is (= :error (:status @transport/connection-state)))
      (is (= identity (:identity @transport/connection-state))))
    (receive! socket "{:type :pong}")
    (is (= identity (:identity @transport/connection-state)))
    (is (= :ready (:status @transport/connection-state)))))

(deftest full-room-is-recorded-without-assigning-an-identity
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket "{:type :ready}")
    (receive! socket "{:type :room-full}")
    (receive! socket "{:type :pong}")
    (is (= :full (:admission @transport/connection-state)))
    (is (nil? (:identity @transport/connection-state)))
    (is (= :ready (:status @transport/connection-state)))
    (is (identical? socket @transport/connection))
    (is (zero? (aget socket "closeCount")))))

(deftest replaced-or-closed-sessions-clear-their-identity
  (transport/start! "http://arena.example/")
  (let [old @transport/connection
        identity-message "{:type :identity :player-id :player-1 :color :cyan}"]
    (open! old)
    (receive! old identity-message)
    (let [old-handler (.-onmessage old)]
      (transport/start! "http://arena.example/")
      (is (nil? (:identity @transport/connection-state)))
      (is (nil? (:admission @transport/connection-state)))
      (old-handler #js {:data identity-message})
      (is (nil? (:identity @transport/connection-state)))
      (let [current @transport/connection]
        (open! current)
        (receive! current "{:type :identity :player-id :player-2 :color :crimson}")
        (is (= :player-2 (get-in @transport/connection-state [:identity :player-id])))
        ((.-onclose current) #js {})
        (is (nil? (:identity @transport/connection-state)))
        (is (nil? (:admission @transport/connection-state)))
        (is (nil? @transport/connection))))))

(deftest restart-detaches-old-callbacks-and-ignores-queued-events
  (transport/start! "http://arena.example/")
  (let [old @transport/connection
        handlers (mapv #(aget old %) ["onopen" "onmessage" "onerror" "onclose"])]
    (transport/start! "http://arena.example/")
    (let [current @transport/connection
          state @transport/connection-state]
      (is (= 2 (count @sockets)))
      (is (not (identical? old current)))
      (is (= 1 (aget old "closeCount")))
      (doseq [property ["onopen" "onmessage" "onerror" "onclose"]]
        (is (nil? (aget old property))))
      (doseq [handler handlers]
        (handler #js {:data "{:type :pong}"}))
      (is (= state @transport/connection-state))
      (is (identical? current @transport/connection))
      (is (zero? (alength (aget old "sent"))))
      (open! current)
      (is (= 1 (alength (aget current "sent"))))
      (transport/stop!)
      (transport/stop!)
      (is (= 1 (aget current "closeCount")))
      (is (nil? @transport/connection))
      (is (= {:status :closed} @transport/connection-state)))))

(deftest stopping-respects-websocket-ready-state
  (doseq [ready-state [0 1 2 3]]
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (set! (.-readyState socket) ready-state)
      (transport/stop!)
      (is (= (if (< ready-state 2) 1 0) (aget socket "closeCount")))
      (is (nil? @transport/connection)))))

(deftest network-errors-and-remote-close-have-explicit-status
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    ((.-onerror socket) #js {})
    (is (= :error (:status @transport/connection-state)))
    (is (= "Servidor no disponible; el juego continúa en modo local."
           (.-textContent @status-element)))
    (set! (.-readyState socket) 3)
    ((.-onclose socket) #js {})
    (is (nil? @transport/connection))
    (is (= :closed (:status @transport/connection-state)))
    (transport/stop!)
    (is (zero? (aget socket "closeCount")))))

(deftest unavailable-websocket-or-invalid-configuration-does-not-throw
  (testing "constructor failure is an error without a retained connection"
    (set! (.-WebSocket js/globalThis)
          (fn [_] (throw (js/Error. "Connection unavailable"))))
    (transport/start! "http://arena.example/")
    (is (= {:status :error} @transport/connection-state))
    (is (nil? @transport/connection)))
  (testing "an invalid override fails before allocating a socket"
    (set! (.-WebSocket js/globalThis) fake-websocket)
    (transport/start! "http://arena.example/?server=invalid")
    (is (= {:status :error} @transport/connection-state))
    (is (empty? @sockets))))

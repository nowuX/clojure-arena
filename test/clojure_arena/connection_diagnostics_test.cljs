(ns clojure-arena.connection-diagnostics-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure-arena.game :as game]
            [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.transport :as transport]))

(def ^:private sockets (atom []))
(def ^:private status-element (atom nil))

(defn- fake-websocket [url]
  (let [socket #js {:url url :readyState 0 :sent #js [] :closeCount 0}]
    (set! (.-send socket)
          (fn [message]
            (.push (aget socket "sent") message)))
    (set! (.-close socket)
          (fn [code reason]
            (aset socket "closeCode" code)
            (aset socket "closeReason" reason)
            (aset socket "closeCount" (inc (aget socket "closeCount")))
            (set! (.-readyState socket) 2)))
    (swap! sockets conj socket)
    socket))

(defn- restore-global! [property descriptor]
  (if descriptor
    (.defineProperty js/Object js/globalThis property descriptor)
    (js-delete js/globalThis property)))

(defn- diagnostics-fixture [test-fn]
  (let [old-document (.getOwnPropertyDescriptor js/Object js/globalThis "document")
        old-websocket (.getOwnPropertyDescriptor js/Object js/globalThis "WebSocket")
        old-state @transport/connection-state
        element #js {:textContent ""}]
    (reset! sockets [])
    (reset! status-element element)
    (set! (.-document js/globalThis)
          #js {:getElementById (fn [id]
                                 (when (= id "connection-status") element))})
    (set! (.-WebSocket js/globalThis) fake-websocket)
    (try
      (test-fn)
      (finally
        (transport/stop!)
        (reset! transport/connection-state old-state)
        (reset! status-element nil)
        (restore-global! "document" old-document)
        (restore-global! "WebSocket" old-websocket)))))

(use-fixtures :each diagnostics-fixture)

(defn- status-text []
  (.-textContent @status-element))

(defn- status-includes? [fragment]
  (.includes (status-text) fragment))

(defn- open! [socket]
  (set! (.-readyState socket) 1)
  ((.-onopen socket) #js {}))

(defn- receive! [socket text]
  ((.-onmessage socket) #js {:data text}))

(defn- snapshot-frame [tick world]
  (pr-str {:type :snapshot :tick tick :world world}))

(def ^:private green-world
  (match-helpers/running-world
   (assoc-in game/initial-world [:players :player-1 :color] "#4ade80")))

(def ^:private green-identity
  {:player-id :player-1 :color :green})

(defn- admit-green! [socket]
  (open! socket)
  (receive! socket (pr-str (assoc green-identity :type :identity))))

(deftest invalid-address-and-websocket-construction-have-actionable-diagnostics
  (testing "an invalid override is reported before a socket is created"
    (transport/start! "http://arena.example/?server=ws%3A%2F%2Fgame.example")
    (is (= {:status :error :error {:kind :invalid-configuration}}
           @transport/connection-state))
    (is (nil? @transport/connection))
    (is (empty? @sockets))
    (is (.startsWith (status-text) "Modo local."))
    (is (status-includes? "dirección"))
    (is (not (status-includes? "Servidor:"))))

  (testing "a constructor failure keeps the attempted endpoint"
    (set! (.-WebSocket js/globalThis)
          (fn [_url]
            (throw (js/Error. "Synthetic browser failure"))))
    (transport/start! "http://arena.example:8082/")
    (is (= {:status :error
            :url "ws://arena.example:8082/ws"
            :error {:kind :websocket-unavailable}}
           @transport/connection-state))
    (is (nil? @transport/connection))
    (is (.startsWith (status-text) "Modo local."))
    (is (status-includes? "WebSocket"))
    (is (status-includes? "Servidor: ws://arena.example:8082/ws."))))

(deftest browser-network-and-send-failures-do-not-invent-causes
  (testing "a browser error reports only the network category and keeps an admitted session"
    (transport/start! "http://arena.example:8082/")
    (let [socket @transport/connection]
      (admit-green! socket)
      (receive! socket (snapshot-frame 1 green-world))
      ((.-onerror socket)
       #js {:message "Synthetic DNS failure"
            :error (js/Error. "Synthetic TLS detail")})
      (is (= {:kind :network} (:error @transport/connection-state)))
      (is (= "ws://arena.example:8082/ws" (:url @transport/connection-state)))
      (is (= green-identity (:identity @transport/connection-state)))
      (is (= {:tick 1 :world green-world} (:snapshot @transport/connection-state)))
      (is (true? (transport/connected?))
          "diagnostic state does not change shared simulation authority")
      (is (.startsWith (status-text) "Modo compartido."))
      (is (status-includes? "WebSocket"))
      (is (not (status-includes? "DNS")))
      (is (not (status-includes? "TLS")))
      (is (status-includes? "Servidor: ws://arena.example:8082/ws."))))

  (testing "opening does not erase a diagnostic before a successful reply"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      ((.-onerror socket) #js {})
      (is (= {:kind :network} (:error @transport/connection-state)))
      (open! socket)
      (is (= {:kind :network} (:error @transport/connection-state)))
      (is (status-includes? "WebSocket"))
      (receive! socket "{:type :pong}")
      (is (nil? (:error @transport/connection-state)))))

  (testing "a failed ping send is classified and a valid pong clears it"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (set! (.-send socket)
            (fn [_message]
              (throw (js/Error. "Synthetic send detail"))))
      (open! socket)
      (is (= {:kind :send-failed} (:error @transport/connection-state)))
      (is (= "ws://arena.example/ws" (:url @transport/connection-state)))
      (is (status-includes? "enviar"))
      (is (not (status-includes? "Synthetic")))
      (receive! socket "{:type :pong}")
      (is (nil? (:error @transport/connection-state)))
      (is (= :ready (:status @transport/connection-state)))))

  (testing "a failed admitted input send retains shared mode and its endpoint"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (admit-green! socket)
      (set! (.-send socket)
            (fn [_message]
              (throw (js/Error. "Synthetic input failure"))))
      (transport/send-input! {:horizontal 1 :jump? true :drop? false :attack? false})
      (is (= {:kind :send-failed} (:error @transport/connection-state)))
      (is (= green-identity (:identity @transport/connection-state)))
      (is (true? (transport/connected?)))
      (is (.startsWith (status-text) "Modo compartido."))
      (is (status-includes? "Servidor: ws://arena.example/ws.")))))

(deftest decode-validation-and-server-errors-stay-distinct
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket "{")
    (is (= {:kind :decode-failed} (:error @transport/connection-state)))
    (is (status-includes? "EDN"))
    (receive! socket "{:type :pong :extra true}")
    (is (= {:kind :invalid-server-message} (:error @transport/connection-state)))
    (is (status-includes? "formato no válido"))
    (doseq [code [:invalid-edn :invalid-message :unsupported-type]]
      (receive! socket (pr-str {:type :error :code code}))
      (is (= {:kind :server :code code} (:error @transport/connection-state)))
      (is (status-includes? (name code)))
      (receive! socket "{:type :pong}")
      (is (nil? (:error @transport/connection-state))
          "a valid pong clears the previous transient diagnostic"))))

(deftest irrelevant-snapshots-preserve-an-error-until-a-newer-assigned-snapshot
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection
        first-snapshot {:tick 7 :world green-world}
        stale-world (assoc-in green-world [:players :player-1 :x] 500)
        unassigned-world (match-helpers/running-world
                          (update green-world :players dissoc :player-1))
        newer-world (assoc-in green-world [:players :player-1 :x] 600)]
    (admit-green! socket)
    (receive! socket (snapshot-frame 7 green-world))
    (receive! socket "{")
    (is (= {:kind :decode-failed} (:error @transport/connection-state)))
    (doseq [frame [(snapshot-frame 7 stale-world)
                   (snapshot-frame 8 unassigned-world)]]
      (let [message (protocol/decode frame)]
        (is (true? (protocol/snapshot-message? message)))
        (receive! socket frame)
        (is (= {:kind :decode-failed} (:error @transport/connection-state))
            "a stale or unassigned snapshot does not clear the diagnostic")
        (is (= first-snapshot (:snapshot @transport/connection-state)))
        (is (true? (transport/connected?)))))
    (receive! socket (snapshot-frame 8 newer-world))
    (is (nil? (:error @transport/connection-state))
        "a newer valid snapshot for the admitted player clears the diagnostic")
    (is (= {:tick 8 :world newer-world} (:snapshot @transport/connection-state)))
    (is (true? (transport/connected?)))
    (is (status-includes? "Modo compartido."))
    (is (status-includes? "· green."))))

(deftest terminal-full-and-timeout-closes-retain-close-metadata
  (testing "full-room rejection remains terminal after close"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (open! socket)
      (receive! socket "{:type :room-full}")
      (is (= 1 (aget socket "closeCount")))
      (is (= "Room full" (aget socket "closeReason")))
      ((.-onclose socket) #js {:code 1000 :reason "Room full"})
      (is (= :full (:admission @transport/connection-state)))
      (is (= :full (:status @transport/connection-state)))
      (is (= "ws://arena.example/ws" (:url @transport/connection-state)))
      (is (= 1000 (:close-code @transport/connection-state)))
      (is (= "Room full" (:close-reason @transport/connection-state)))
      (is (nil? @transport/connection))
      (is (.startsWith (status-text) "Modo local."))
      (is (status-includes? "sala está llena"))
      (is (status-includes? "Código de cierre: 1000."))
      (is (status-includes? "Motivo: Room full"))
      (is (status-includes? "Servidor: ws://arena.example/ws."))))

  (testing "timeout rejection and its server diagnostic survive close"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (open! socket)
      (receive! socket "{:type :error :code :connection-timeout}")
      (is (= :expired (:admission @transport/connection-state)))
      (is (= {:kind :server :code :connection-timeout}
             (:error @transport/connection-state)))
      (is (= "Connection timeout" (aget socket "closeReason")))
      ((.-onclose socket) #js {:code 1000 :reason "Connection timeout"})
      (is (= :expired (:admission @transport/connection-state)))
      (is (= :expired (:status @transport/connection-state)))
      (is (= {:kind :server :code :connection-timeout}
             (:error @transport/connection-state)))
      (is (= 1000 (:close-code @transport/connection-state)))
      (is (= "Connection timeout" (:close-reason @transport/connection-state)))
      (is (nil? @transport/connection))
      (is (.startsWith (status-text) "Modo local."))
      (is (status-includes? "Conexión expirada"))
      (is (status-includes? "connection-timeout"))
      (is (status-includes? "Código de cierre: 1000."))
      (is (status-includes? "Motivo: Connection timeout"))
      (is (status-includes? "Servidor: ws://arena.example/ws.")))))

(deftest replacement-sockets-ignore-all-old-session-callbacks
  (transport/start! "http://arena.example:8082/")
  (let [old-socket @transport/connection]
    (admit-green! old-socket)
    (receive! old-socket (snapshot-frame 20 green-world))
    (let [old-message-handler (.-onmessage old-socket)
          old-error-handler (.-onerror old-socket)
          old-close-handler (.-onclose old-socket)]
      (transport/start! "https://arena.example:8443/game")
      (let [replacement @transport/connection
            replacement-state @transport/connection-state]
        (is (not (identical? old-socket replacement)))
        (is (= "wss://arena.example:8443/ws" (:url replacement-state)))
        (old-message-handler #js {:data "{:type :error :code :invalid-message}"})
        (old-error-handler #js {:message "Stale callback detail"})
        (old-close-handler #js {:code 4001 :reason "Stale close"})
        (is (= replacement-state @transport/connection-state))
        (is (identical? replacement @transport/connection))
        (is (nil? (:identity @transport/connection-state)))
        (is (nil? (:snapshot @transport/connection-state)))
        (is (status-includes? "Modo local."))
        (is (status-includes? "Servidor: wss://arena.example:8443/ws."))))))

(ns clojure-arena.transport-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.transport :as transport]))

(def ^:private sockets (atom []))
(def ^:private status-element (atom nil))

(defn- fake-websocket [url]
  (let [socket #js {:url url :readyState 0 :sent #js [] :closeCount 0}]
    (set! (.-send socket) (fn [message] (.push (aget socket "sent") message)))
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

(defn- sent-frames [socket]
  (mapv protocol/decode (array-seq (aget socket "sent"))))

(defn- sent-inputs [socket]
  (->> (sent-frames socket)
       (filter #(= :input (:type %)))
       (mapv #(dissoc % :type))))

(defn- admit! [socket]
  (open! socket)
  (receive! socket "{:type :identity :player-id :player-41 :color :green}"))

(defn- snapshot-frame [tick world]
  (pr-str {:type :snapshot :tick tick :world (match-helpers/running-world world)}))

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
    (is (false? (transport/connected?)))
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
    (is (= "Modo local. Comunicación con el servidor verificada. Servidor: ws://arena.example:8082/ws."
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
      (is (true? (transport/connected?)))
      (is (= :ready (:status @transport/connection-state)))
      (is (= "Modo compartido. Esperando estado de la arena… Servidor: ws://arena.example/ws. ID: player-41 · green."
             (.-textContent @status-element)))
      (receive! socket message)
      (is (= identity (:identity @transport/connection-state)))
      (is (= 1 (alength (aget socket "sent")))))))

(deftest snapshots-wait-for-open-admitted-player-and-ignore-full-rooms
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket (snapshot-frame 4 (match-helpers/running-world game/initial-world)))
    (is (nil? (:snapshot @transport/connection-state))
        "a valid snapshot before admission does not change connection state")
    (receive! socket "{:type :identity :player-id :player-1 :color :cyan}")
    (receive! socket
              (snapshot-frame 4 (update (match-helpers/running-world game/initial-world) :players dissoc :player-1)))
    (is (nil? (:snapshot @transport/connection-state))
        "a snapshot without the assigned player is ignored")
    (receive! socket (snapshot-frame 4 (match-helpers/running-world game/initial-world)))
    (is (= {:tick 4 :world (match-helpers/running-world game/initial-world)}
           (:snapshot @transport/connection-state)))
    (is (= "Modo compartido. Arena compartida conectada. Servidor: ws://arena.example/ws. ID: player-1 · cyan."
           (.-textContent @status-element))))

  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket "{:type :room-full}")
    (let [full-state @transport/connection-state]
      (receive! socket (snapshot-frame 4 (match-helpers/running-world game/initial-world)))
      (is (= full-state @transport/connection-state)
          "a valid snapshot after a full-room rejection is ignored"))))

(deftest snapshots-are-monotonic-and-malformed-frames-preserve-the-last-valid-world
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection
        first-world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-1 :x] 111)
        duplicate-world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-1 :x] 222)
        newest-world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-1 :x] 333)]
    (open! socket)
    (receive! socket "{:type :identity :player-id :player-1 :color :cyan}")
    (receive! socket (snapshot-frame 7 first-world))
    (receive! socket "{:type :pong}")
    (is (= {:tick 7 :world first-world}
           (:snapshot @transport/connection-state))
        "ping status updates preserve the current snapshot")
    (is (= :player-1 (get-in @transport/connection-state [:identity :player-id]))
        "ping status updates preserve the assigned identity")
    (doseq [frame [(snapshot-frame 7 duplicate-world)
                    (snapshot-frame 6 duplicate-world)]]
      (receive! socket frame)
      (is (= {:tick 7 :world first-world}
             (:snapshot @transport/connection-state))
          "equal and older ticks cannot replace the accepted snapshot"))
    (doseq [frame [(pr-str {:type :snapshot :tick -1 :world newest-world})
                   (pr-str {:type :snapshot :tick 7.5 :world newest-world})
                   (pr-str {:type :snapshot :tick 8 :world newest-world :extra true})]]
      (receive! socket frame)
      (is (= {:tick 7 :world first-world}
             (:snapshot @transport/connection-state))
          "a malformed snapshot leaves the last valid snapshot untouched"))
    (is (= :accepted (:admission @transport/connection-state)))
    (is (= :player-1 (get-in @transport/connection-state [:identity :player-id])))
    (receive! socket (snapshot-frame 8 newest-world))
    (is (= {:tick 8 :world newest-world}
           (:snapshot @transport/connection-state)))))

(deftest a-closed-current-socket-cannot-install-a-snapshot
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket "{:type :identity :player-id :player-1 :color :cyan}")
    (set! (.-readyState socket) 3)
    (let [closed-state @transport/connection-state]
      (receive! socket (snapshot-frame 1 (match-helpers/running-world game/initial-world)))
      (is (= closed-state @transport/connection-state)
          "the current callback is ignored once its WebSocket is closed")
      ((.-onclose socket) #js {})
      (is (nil? (:snapshot @transport/connection-state))
          "close clears the per-session snapshot tracker"))))

(deftest a-new-socket-accepts-lower-ticks-and-old-snapshot-callbacks-are-ignored
  (transport/start! "http://arena.example/")
  (let [old-socket @transport/connection]
    (open! old-socket)
    (receive! old-socket "{:type :identity :player-id :player-1 :color :cyan}")
    (receive! old-socket (snapshot-frame 20 (match-helpers/running-world game/initial-world)))
    (let [old-handler (.-onmessage old-socket)]
      (transport/start! "http://arena.example/")
      (let [new-socket @transport/connection]
        (is (nil? (:snapshot @transport/connection-state)))
        (old-handler #js {:data (snapshot-frame 99 (match-helpers/running-world game/initial-world))})
        (is (nil? (:snapshot @transport/connection-state))
            "a stale socket callback cannot affect the replacement")
        (open! new-socket)
        (receive! new-socket "{:type :identity :player-id :player-1 :color :cyan}")
        (receive! new-socket (snapshot-frame 1 (match-helpers/running-world game/initial-world)))
        (is (= {:tick 1 :world (match-helpers/running-world game/initial-world)}
               (:snapshot @transport/connection-state))
            "a replacement session starts a fresh tick sequence")))))

(deftest connected-requires-an-open-accepted-socket-but-ignores-status-errors
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (is (false? (transport/connected?))
        "connecting is not connected before the WebSocket opens")
    (open! socket)
    (is (false? (transport/connected?))
        "an open socket is not connected until identity admission")
    (receive! socket "{:type :identity :player-id :player-41 :color :green}")
    (is (true? (transport/connected?)))
    (receive! socket "not a valid server frame")
    (is (= :error (:status @transport/connection-state)))
    (is (= :accepted (:admission @transport/connection-state)))
    (is (true? (transport/connected?))
        "a transient protocol error does not return an open admitted socket to local physics")
    (set! (.-readyState socket) 3)
    (is (false? (transport/connected?))
        "an admitted socket that is no longer OPEN is disconnected")
    ((.-onclose socket) #js {})
    (is (false? (transport/connected?)))))

(deftest input-contract-is-exact-and-valid-inputs-buffer-until-admission
  (is (= {:horizontal 0 :jump? false :drop? false :attack? false
          :dash? false :antiknockback? false :recover? false :grenade? false :nova? false}
         protocol/neutral-input))
  (is (protocol/player-input? protocol/neutral-input))
  (doseq [input [(assoc protocol/neutral-input :extra true)
                 (dissoc protocol/neutral-input :drop?)
                 (assoc protocol/neutral-input :horizontal 2)
                 (assoc protocol/neutral-input :jump? 1)]]
    (is (not (protocol/player-input? input))))

  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :jump? true))
    (is (empty? (sent-inputs socket))
        "a valid input waits while the socket is connecting")
    (open! socket)
    (transport/send-input! (assoc protocol/neutral-input :horizontal -1 :drop? true))
    (transport/send-input! (assoc protocol/neutral-input :attack? true))
    (doseq [input [(assoc protocol/neutral-input :extra true)
                   (dissoc protocol/neutral-input :attack?)
                   (assoc protocol/neutral-input :horizontal 4)
                   (assoc protocol/neutral-input :attack? :yes)]]
      (transport/send-input! input))
    (is (empty? (sent-inputs socket))
        "an open but unadmitted socket sends no player input")
    (receive! socket "{:type :identity :player-id :player-41 :color :green}")
    (is (= [(assoc protocol/neutral-input :jump? true :drop? true :attack? true)]
           (sent-inputs socket))
        "admission flushes one exact input with latest movement and coalesced actions")
    (is (= (assoc protocol/neutral-input :type :input :jump? true :drop? true :attack? true)
           (last (sent-frames socket))))))

(deftest accepted-input-sends-immediately-without-an-unsolicited-neutral
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (admit! socket)
    (is (empty? (sent-inputs socket))
        "identity alone does not send a neutral input")
    (let [input (assoc protocol/neutral-input :horizontal -1 :jump? true :attack? true)]
      (transport/send-input! input)
      (is (= [input] (sent-inputs socket))
          "accepted open sessions send the current input synchronously")
      (is (= (assoc protocol/neutral-input :type :input :horizontal -1 :jump? true :attack? true)
             (last (sent-frames socket)))))))

(deftest full-room-does-not-buffer-and-clears-pending-input
  (transport/start! "http://arena.example/")
  (let [full-socket @transport/connection]
    (open! full-socket)
    (transport/send-input! (assoc protocol/neutral-input :horizontal -1 :jump? true))
    (receive! full-socket "{:type :room-full}")
    (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :attack? true))
    (is (= :full (:admission @transport/connection-state)))
    (is (= 2 (.-readyState full-socket))
        "a full-room rejection immediately starts closing the socket")
    (is (= 1 (aget full-socket "closeCount")))
    (is (empty? (sent-inputs full-socket)))
    ((.-onclose full-socket) #js {})
    (is (nil? @transport/connection))
    (is (= :full (:admission @transport/connection-state))
        "the rejection remains recorded after the close event")
    (is (false? (transport/connected?)))
    (transport/stop!)
    (is (= {:status :closed} @transport/connection-state)
        "an explicit stop clears the rejected session state")

    (transport/start! "http://arena.example/")
    (let [replacement @transport/connection]
      (admit! replacement)
      (is (empty? (sent-inputs replacement))
          "neither pre-rejection nor full-room input crosses into a later session"))))

(deftest disconnect-drops-buffered-input-and-no-socket-input-is-not-replayed
  (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :jump? true))
  (transport/start! "http://arena.example/")
  (let [disconnected @transport/connection]
    (transport/send-input! (assoc protocol/neutral-input :horizontal -1 :drop? true))
    (set! (.-readyState disconnected) 3)
    ((.-onclose disconnected) #js {})
    (transport/send-input! (assoc protocol/neutral-input :attack? true))
    (is (nil? @transport/connection))
    (transport/start! "http://arena.example/")
    (let [current @transport/connection]
      (admit! current)
      (is (empty? (sent-inputs current))
          "inputs with no socket and from the disconnected session are discarded"))))

(deftest replacement-session-ignores-stale-admission-and-keeps-only-current-input
  (transport/start! "http://arena.example/")
  (let [old @transport/connection
        old-message-handler (.-onmessage old)]
    (open! old)
    (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :jump? true))
    (transport/start! "http://arena.example/")
    (let [current @transport/connection]
      (open! current)
      (transport/send-input! (assoc protocol/neutral-input :horizontal -1 :drop? true))
      (old-message-handler #js {:data "{:type :identity :player-id :player-41 :color :green}"})
      (is (nil? (:identity @transport/connection-state))
          "a stale identity callback cannot admit the replacement")
      (receive! current "{:type :identity :player-id :player-42 :color :amber}")
      (is (= [(assoc protocol/neutral-input :horizontal -1 :drop? true)]
             (sent-inputs current))
          "only the replacement session's pending input is flushed"))))

(deftest reconnect-after-loss-keeps-only-the-new-session-buffer-and-watermark
  (transport/start! "http://arena.example/")
  (let [old @transport/connection]
    (admit! old)
    (let [old-world (assoc (match-helpers/running-world game/initial-world) :players
                           {:player-41 (game/make-player "#4ade80" 600 240)})
          fresh-world (assoc (match-helpers/running-world game/initial-world) :players
                             {:player-42 (game/make-player "#4ade80" 460 160)})
          old-handlers (mapv #(aget old %)
                             ["onopen" "onmessage" "onerror" "onclose"])]
      (receive! old (snapshot-frame 80 old-world))
      (set! (.-send old) (fn [_] (throw (js/Error. "Transport lost"))))
      (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :jump? true :drop? true :attack? true))
      (set! (.-readyState old) 3)
      ((.-onclose old) #js {})
      (transport/start! "http://arena.example/")
      (let [current @transport/connection
            new-input (assoc protocol/neutral-input :horizontal -1 :jump? true)]
        (transport/send-input! new-input)
        (doseq [handler old-handlers]
          (handler #js {:data "{:type :identity :player-id :player-41 :color :green}"}))
        (is (nil? (:identity @transport/connection-state)))
        (is (nil? (:snapshot @transport/connection-state)))
        (open! current)
        (receive! current "{:type :identity :player-id :player-42 :color :green}")
        (is (= [new-input] (sent-inputs current))
            "only input captured after reconnection is sent to the new owner")
        (receive! current (snapshot-frame 1 fresh-world))
        ((second old-handlers) #js {:data (snapshot-frame 100 old-world)})
        (is (= {:player-id :player-42 :color :green}
               (:identity @transport/connection-state)))
        (is (= {:tick 1 :world (match-helpers/running-world fresh-world)}
               (:snapshot @transport/connection-state)))
        ((last old-handlers) #js {})
        (is (transport/connected?))
        (is (identical? current @transport/connection))))))

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

(deftest full-room-rejection-wins-message-and-error-ordering-through-close
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection]
    (open! socket)
    (receive! socket "{:type :ready}")
    (receive! socket "{:type :identity :player-id :player-1 :color :cyan}")
    (receive! socket (snapshot-frame 4 (match-helpers/running-world game/initial-world)))
    (is (transport/connected?))
    (is (= {:tick 4 :world (match-helpers/running-world game/initial-world)}
           (:snapshot @transport/connection-state))
        "the session has a valid snapshot for rejection to clear")

    (receive! socket "{:type :room-full}")
    (let [rejected-state @transport/connection-state]
      (is (= :full (:admission rejected-state)))
      (is (= :full (:status rejected-state)))
      (is (nil? (:identity rejected-state)))
      (is (nil? (:snapshot rejected-state)))
      (is (false? (transport/connected?)))
      (is (= "Modo local. La sala está llena; el juego continúa en modo local. Servidor: ws://arena.example/ws."
             (.-textContent @status-element)))
      (is (= 2 (.-readyState socket)))
      (is (= 1 (aget socket "closeCount")))
      (is (= 1000 (aget socket "closeCode")))
      (is (= "Room full" (aget socket "closeReason")))

      (receive! socket "{:type :ready}")
      (receive! socket "{:type :pong}")
      (receive! socket "{:type :identity :player-id :player-42 :color :amber}")
      (receive! socket (snapshot-frame 5 (match-helpers/running-world game/initial-world)))
      (receive! socket "{:type :error :code :connection-timeout}")
      ((.-onerror socket) #js {})
      (is (= rejected-state @transport/connection-state)
          "later messages and errors cannot replace the rejection")
      (is (= 1 (aget socket "closeCount"))
          "repeated callbacks cannot issue another close")

      ((.-onclose socket) #js {})
      (is (nil? @transport/connection))
      (is (= rejected-state @transport/connection-state)
          "close preserves the full-room status and its message")
      (receive! socket "{:type :pong}")
      (receive! socket "{:type :error :code :connection-timeout}")
      ((.-onerror socket) #js {})
      (is (= rejected-state @transport/connection-state)
          "queued callbacks after close are ignored")
      (is (= "Modo local. La sala está llena; el juego continúa en modo local. Servidor: ws://arena.example/ws."
             (.-textContent @status-element)))))

  (testing "a connecting socket is also closed after a rejection"
    (transport/start! "http://arena.example/")
    (let [socket @transport/connection]
      (receive! socket "{:type :room-full}")
      (is (= 2 (.-readyState socket)))
      (is (= 1 (aget socket "closeCount")))
      (receive! socket "{:type :room-full}")
      (is (= 1 (aget socket "closeCount"))
          "a rejected session requests close exactly once"))))

(deftest connection-timeout-is-terminal-clears-session-and-closes-once
  (transport/start! "http://arena.example/")
  (let [socket @transport/connection
        send-message (.-send socket)
        world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-1 :x] 321)]
    (open! socket)
    (receive! socket "{:type :identity :player-id :player-1 :color :cyan}")
    (receive! socket (snapshot-frame 7 world))
    (set! (.-send socket)
          (fn [_]
            (throw (js/Error. "Simulated send failure"))))
    (transport/send-input! (assoc protocol/neutral-input :horizontal 1 :jump? true))
    (is (= :error (:status @transport/connection-state)))
    (is (= world (get-in @transport/connection-state [:snapshot :world])))

    (set! (.-send socket) send-message)
    (receive! socket "{:type :error :code :connection-timeout}")
    (let [expired-state @transport/connection-state]
      (is (= :expired (:admission expired-state)))
      (is (= :expired (:status expired-state)))
      (is (nil? (:identity expired-state)))
      (is (nil? (:snapshot expired-state)))
      (is (= {:type :error :code :connection-timeout} (:message expired-state)))
      (is (false? (transport/connected?)))
      (is (= "Modo local. Conexión expirada (connection-timeout); el juego continúa en modo local. Servidor: ws://arena.example/ws."
             (.-textContent @status-element)))
      (is (= 2 (.-readyState socket)))
      (is (= 1 (aget socket "closeCount")))
      (is (= 1000 (aget socket "closeCode")))
      (is (= "Connection timeout" (aget socket "closeReason")))
      (is (empty? (sent-inputs socket))
          "the buffered input that failed to send is discarded on timeout")

      ;; A delayed close must not let another input enter the expired session buffer.
      (set! (.-readyState socket) 1)
      (transport/send-input! (assoc protocol/neutral-input :horizontal -1 :drop? true))
      (receive! socket "{:type :ready}")
      (receive! socket "{:type :identity :player-id :player-42 :color :amber}")
      (receive! socket (snapshot-frame 8 (match-helpers/running-world game/initial-world)))
      (receive! socket "{:type :room-full}")
      ((.-onerror socket) #js {})
      (is (= expired-state @transport/connection-state)
          "late messages and errors cannot replace the expired state")
      (is (= 1 (aget socket "closeCount"))
          "late messages cannot issue another close")

      ((.-onclose socket) #js {})
      (is (nil? @transport/connection))
      (is (= expired-state @transport/connection-state)
          "the close callback preserves expiration")
      ((.-onerror socket) #js {})
      (receive! socket "{:type :error :code :connection-timeout}")
      (is (= expired-state @transport/connection-state)
          "queued callbacks after close remain harmless")

      (transport/stop!)
      (is (= {:status :closed} @transport/connection-state)
          "an explicit stop clears the expired session state")
      (transport/start! "http://arena.example/")
      (let [replacement @transport/connection]
        (is (nil? (:admission @transport/connection-state)))
        (admit! replacement)
        (is (empty? (sent-inputs replacement))
            "expired-session input is not replayed after a clean retry")))))

(deftest replaced-or-closed-sessions-clear-their-identity
  (transport/start! "http://arena.example/")
  (let [old @transport/connection
        identity-message "{:type :identity :player-id :player-1 :color :cyan}"]
    (open! old)
    (receive! old identity-message)
    (receive! old (snapshot-frame 4 (match-helpers/running-world game/initial-world)))
    (is (= 4 (get-in @transport/connection-state [:snapshot :tick])))
    (let [old-handler (.-onmessage old)]
      (transport/start! "http://arena.example/")
      (is (nil? (:identity @transport/connection-state)))
      (is (nil? (:snapshot @transport/connection-state)))
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
        (is (nil? (:snapshot @transport/connection-state)))
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
    (is (= "Modo local. Se produjo un error en la conexión WebSocket. Comprueba la dirección y que el servidor esté en ejecución. Servidor: ws://arena.example/ws."
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
    (is (= {:status :error
            :url "ws://arena.example/ws"
            :error {:kind :websocket-unavailable}}
           @transport/connection-state))
    (is (nil? @transport/connection)))
  (testing "an invalid override fails before allocating a socket"
    (set! (.-WebSocket js/globalThis) fake-websocket)
    (transport/start! "http://arena.example/?server=invalid")
    (is (= {:status :error :error {:kind :invalid-configuration}}
           @transport/connection-state))
    (is (empty? @sockets))))

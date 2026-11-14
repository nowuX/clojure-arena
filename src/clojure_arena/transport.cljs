(ns clojure-arena.transport
  (:require [clojure-arena.protocol :as protocol]))

(defonce connection (atom nil))
(defonce connection-state (atom {:status :closed}))
(defonce ^:private pending-input (atom nil))

(defn connected? []
  (let [socket @connection]
    (boolean (and socket
                  (= 1 (.-readyState socket))
                  (= :accepted (:admission @connection-state))))))

(defn websocket-url [page-url]
  (let [page (js/URL. page-url)
        configured (.get (.-searchParams page) "server")
        server (js/URL. (if (seq configured) configured (.-origin page)))
        scheme ({"http:" "ws:" "https:" "wss:"} (.-protocol server))]
    (when-not scheme
      (throw (ex-info "Expected an HTTP or HTTPS game server URL" {})))
    (str scheme "//" (.-host server) "/ws")))

(defn set-state! [state]
  (reset! connection-state state)
  (when-let [element (.getElementById js/document "connection-status")]
    (let [label (cond
                  (= :full (:admission state))
                  "La sala está llena; el juego continúa en modo local."

                  (= :expired (:admission state))
                  "Conexión expirada; el juego continúa en modo local."

                  (= :accepted (:admission state))
                  (if (:snapshot state)
                    "Arena compartida conectada."
                    "Esperando estado de la arena…")

                  :else
                  (case (:status state)
                    :connecting "Conectando al servidor…"
                    :open "Conectado al servidor."
                    :ready "Comunicación con el servidor verificada."
                    :error "Servidor no disponible; el juego continúa en modo local."
                    "Conexión cerrada; el juego continúa en modo local."))
          {:keys [player-id color]} (:identity state)]
      (set! (.-textContent element)
            (str label (when player-id
                         (str " ID: " (name player-id) " · " (name color) ".")))))))

(defn- terminal-admission? [state]
  (contains? #{:full :expired} (:admission state)))

(defn- close-terminal-socket! [socket reason]
  (when (< (.-readyState socket) 2)
    (try
      (.close socket 1000 reason)
      (catch :default _ nil))))

(defn- flush-input! []
  (when-let [socket @connection]
    (when (and (= 1 (.-readyState socket))
               (= :accepted (:admission @connection-state)))
      (when-let [input @pending-input]
        (try
          (.send socket (pr-str (assoc input :type :input)))
          (reset! pending-input nil)
          (catch :default _
            (set-state! (assoc @connection-state :status :error))))))))

(defn send-input! [input]
  (when-let [socket @connection]
    (when (and (protocol/player-input? input)
               (< (.-readyState socket) 2)
               (not (terminal-admission? @connection-state)))
      (swap! pending-input #(protocol/merge-pending-input
                             (or % protocol/neutral-input) input))
      (flush-input!))))

(defn stop! []
  (reset! pending-input nil)
  (when-let [socket @connection]
    (reset! connection nil)
    (set! (.-onopen socket) nil)
    (set! (.-onmessage socket) nil)
    (set! (.-onerror socket) nil)
    (set! (.-onclose socket) nil)
    (when (< (.-readyState socket) 2)
      (.close socket)))
  (set-state! {:status :closed}))

(defn start! [page-url]
  (stop!)
  (try
    (let [url (websocket-url page-url)
          socket (js/WebSocket. url)]
      (reset! connection socket)
      (set-state! {:status :connecting :url url})
      (set! (.-onopen socket)
            (fn [_]
              (when (and (identical? socket @connection)
                         (not (terminal-admission? @connection-state)))
                (set-state! {:status :open :url url})
                (.send socket (pr-str {:type :ping})))))
      (set! (.-onmessage socket)
            (fn [event]
              (when (and (identical? socket @connection)
                         (not (terminal-admission? @connection-state)))
                (try
                  (let [message (protocol/decode (.-data event))]
                    (if-not (protocol/server-message? message)
                      (set-state! (assoc @connection-state :status :error :url url))
                      (let [state (assoc @connection-state :url url :message message)]
                        (if (= :snapshot (:type message))
                          (let [{:keys [tick world]} message
                                player-id (get-in state [:identity :player-id])
                                previous-tick (get-in state [:snapshot :tick])]
                            (when (and (protocol/snapshot-message? message)
                                       (= 1 (.-readyState socket))
                                       (= :accepted (:admission state))
                                       (contains? (:players world) player-id)
                                       (or (nil? previous-tick)
                                           (> tick previous-tick)))
                              (set-state! (assoc state :snapshot
                                                 {:tick tick :world world}))))
                          (do
                            (set-state!
                             (case (:type message)
                               :identity (assoc state :admission :accepted
                                                :identity (select-keys message [:player-id :color]))
                               :room-full (-> state
                                              (assoc :admission :full :status :full)
                                              (dissoc :identity :snapshot))
                               :error (if (= :connection-timeout (:code message))
                                        (-> state
                                            (assoc :admission :expired :status :expired)
                                            (dissoc :identity :snapshot))
                                        (assoc state :status :error))
                               :pong (assoc state :status :ready)
                               :ready (assoc state :status :open)
                               (assoc state :status :error)))
                            (case (:type message)
                              :identity (flush-input!)
                              :room-full (do
                                           (reset! pending-input nil)
                                           (close-terminal-socket! socket "Room full"))
                              :error (when (= :connection-timeout (:code message))
                                       (reset! pending-input nil)
                                       (close-terminal-socket! socket "Connection timeout"))
                              nil))))))
                  (catch :default _
                    (set-state! (assoc @connection-state :status :error :url url)))))))
      (set! (.-onerror socket)
            (fn [_]
              (when (and (identical? socket @connection)
                         (not (terminal-admission? @connection-state)))
                (set-state! (assoc @connection-state :status :error :url url)))))
      (set! (.-onclose socket)
            (fn [_]
              (when (identical? socket @connection)
                (reset! connection nil)
                (reset! pending-input nil)
                (if (terminal-admission? @connection-state)
                  (set-state! (assoc @connection-state
                                     :status (:admission @connection-state)
                                     :url url))
                  (set-state! {:status :closed :url url}))))))
    (catch :default _
      (set-state! {:status :error}))))

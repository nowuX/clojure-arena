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

(defn- error-label [{:keys [kind code]}]
  (case kind
    :network "Se produjo un error en la conexión WebSocket. Comprueba la dirección y que el servidor esté en ejecución."
    :send-failed "No se pudo enviar un mensaje al servidor."
    :decode-failed "Se recibió un mensaje que no se puede leer como EDN."
    :invalid-server-message "Se recibió un mensaje del servidor con un formato no válido."
    :invalid-configuration "La dirección del servidor no es válida. Usa una dirección http:// o https:// en el parámetro server."
    :websocket-unavailable "No se pudo abrir la conexión WebSocket. Comprueba la dirección y la configuración del navegador."
    :server (case code
              :invalid-edn "El servidor rechazó un mensaje con EDN inválido (invalid-edn)."
              :invalid-message "El servidor rechazó un mensaje con formato inválido (invalid-message)."
              :unsupported-type "El servidor rechazó un tipo de mensaje no admitido (unsupported-type)."
              :connection-timeout "La conexión expiró por falta de respuesta (connection-timeout)."
              "El servidor informó de un error.")
    "Se produjo un error de conexión."))

(defn set-state! [state]
  (reset! connection-state state)
  (when-let [element (.getElementById js/document "connection-status")]
    (let [shared? (connected?)
          label (cond
                  (= :full (:admission state))
                  "La sala está llena; el juego continúa en modo local."

                  (= :expired (:admission state))
                  "Conexión expirada (connection-timeout); el juego continúa en modo local."

                  (:error state)
                  (error-label (:error state))

                  shared?
                  (if (:snapshot state)
                    "Arena compartida conectada."
                    "Esperando estado de la arena…")

                  :else
                  (case (:status state)
                    :connecting "Conectando al servidor…"
                    :open "Conectado al servidor."
                    :ready "Comunicación con el servidor verificada."
                    :error "Se produjo un error de conexión."
                    "Conexión cerrada; el juego continúa en modo local."))
          {:keys [player-id color]} (:identity state)]
      (set! (.-textContent element)
            (str (if shared? "Modo compartido. " "Modo local. ")
                 label
                 (when-let [url (:url state)] (str " Servidor: " url "."))
                 (when-let [code (:close-code state)]
                   (str " Código de cierre: " code "."))
                 (when-let [reason (:close-reason state)]
                   (str " Motivo: " reason))
                 (when player-id
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
            (set-state! (assoc @connection-state :status :error
                               :error {:kind :send-failed}))))))))

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

(defn- read-server-frame [text]
  (try
    (let [message (protocol/decode text)]
      (if (protocol/server-message? message)
        {:message message}
        {:error {:kind :invalid-server-message}}))
    (catch :default _
      {:error {:kind :decode-failed}})))

(defn start!
  "Start a new connection session after discarding the previous socket's state."
  [page-url]
  (stop!)
  (let [url (try (websocket-url page-url) (catch :default _ nil))]
    (if-not url
      (set-state! {:status :error :error {:kind :invalid-configuration}})
      (try
        (let [socket (js/WebSocket. url)]
          (reset! connection socket)
          (set-state! {:status :connecting :url url})
          (set! (.-onopen socket)
                (fn [_]
                  (when (and (identical? socket @connection)
                             (not (terminal-admission? @connection-state)))
                    (set-state! (assoc @connection-state :status :open :url url))
                    (try
                      (.send socket (pr-str {:type :ping}))
                      (catch :default _
                        (set-state! (assoc @connection-state :status :error
                                          :error {:kind :send-failed})))))))
          (set! (.-onmessage socket)
                (fn [event]
                  (when (and (identical? socket @connection)
                             (not (terminal-admission? @connection-state)))
                    (let [{:keys [message error]} (read-server-frame (.-data event))]
                      (if error
                        (set-state! (assoc @connection-state :status :error :error error))
                        (let [state (assoc @connection-state :message message)]
                          (if (= :snapshot (:type message))
                            (let [{:keys [tick world]} message
                                  player-id (get-in state [:identity :player-id])
                                  previous-tick (get-in state [:snapshot :tick])]
                              (when (and (= 1 (.-readyState socket))
                                         (= :accepted (:admission state))
                                         (contains? (:players world) player-id)
                                         (or (nil? previous-tick)
                                             (> tick previous-tick)))
                                (set-state! (-> state
                                                (dissoc :error)
                                                (assoc :status :ready :snapshot
                                                       {:tick tick :world world})))))
                            (do
                              (set-state!
                               (case (:type message)
                                 :identity (-> state
                                               (dissoc :error)
                                               (assoc :status :ready :admission :accepted
                                                      :identity (select-keys message [:player-id :color])))
                                 :room-full (-> state
                                                (assoc :admission :full :status :full)
                                                (dissoc :identity :snapshot :error))
                                 :error (let [failed (assoc state :status :error
                                                           :error {:kind :server :code (:code message)})]
                                          (if (= :connection-timeout (:code message))
                                            (-> failed
                                                (assoc :admission :expired :status :expired)
                                                (dissoc :identity :snapshot))
                                            failed))
                                 :pong (-> state (dissoc :error) (assoc :status :ready))
                                 :ready (-> state (dissoc :error) (assoc :status :open))))
                              (case (:type message)
                                :identity (flush-input!)
                                :room-full (do
                                             (reset! pending-input nil)
                                             (close-terminal-socket! socket "Room full"))
                                :error (when (= :connection-timeout (:code message))
                                         (reset! pending-input nil)
                                         (close-terminal-socket! socket "Connection timeout"))
                                nil)))))))))
          (set! (.-onerror socket)
                (fn [_]
                  (when (and (identical? socket @connection)
                             (not (terminal-admission? @connection-state)))
                    (set-state! (assoc @connection-state :status :error
                                      :error {:kind :network})))))
          (set! (.-onclose socket)
                (fn [event]
                  (when (identical? socket @connection)
                    (let [state @connection-state
                          code (.-code event)
                          reason (.-reason event)]
                      (reset! connection nil)
                      (reset! pending-input nil)
                      (set-state!
                       (cond-> (if (terminal-admission? state)
                                 (assoc state :status (:admission state))
                                 (cond-> {:status :closed :url url}
                                   (:error state) (assoc :error (:error state))))
                         (js/Number.isInteger code) (assoc :close-code code)
                         (and (string? reason) (seq reason))
                         (assoc :close-reason reason))))))))
        (catch :default _
          (set-state! {:status :error :url url :error {:kind :websocket-unavailable}}))))))

(ns clojure-arena.transport
  (:require [clojure-arena.protocol :as protocol]))

(defonce connection (atom nil))
(defonce connection-state (atom {:status :closed}))

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
    (set! (.-textContent element)
          (case (:status state)
            :connecting "Conectando al servidor…"
            :open "Conectado al servidor."
            :ready "Comunicación con el servidor verificada."
            :error "Servidor no disponible; el juego continúa en modo local."
            "Conexión cerrada; el juego continúa en modo local."))))

(defn stop! []
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
              (when (identical? socket @connection)
                (set-state! {:status :open :url url})
                (.send socket (pr-str {:type :ping})))))
      (set! (.-onmessage socket)
            (fn [event]
              (when (identical? socket @connection)
                (try
                  (let [message (protocol/decode (.-data event))]
                    (if (protocol/server-message? message)
                      (set-state! {:status (case (:type message)
                                            :pong :ready
                                            :ready :open
                                            :error)
                                   :url url :message message})
                      (set-state! {:status :error :url url})))
                  (catch :default _
                    (set-state! {:status :error :url url}))))))
      (set! (.-onerror socket)
            (fn [_]
              (when (identical? socket @connection)
                (set-state! {:status :error :url url}))))
      (set! (.-onclose socket)
            (fn [_]
              (when (identical? socket @connection)
                (reset! connection nil)
                (set-state! {:status :closed :url url})))))
    (catch :default _
      (set-state! {:status :error}))))

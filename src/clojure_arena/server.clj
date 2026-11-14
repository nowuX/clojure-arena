(ns clojure-arena.server
  (:require [clojure.string :as str]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.publication :as publication]
            [clojure-arena.room :as room]
            [clojure-arena.ticker :as ticker]
            [org.httpkit.server :as http]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.head :refer [wrap-head]]))

(def default-options {:host "127.0.0.1" :port 8082})
(defonce ^:private runtimes (atom {}))

(defn broadcast-world! [connections {:keys [connection-tokens message]}]
  (let [text (pr-str message)
        channels @connections]
    (doseq [token connection-tokens]
      (when-let [channel (get channels token)]
        (try
          (http/send! channel text)
          (catch Exception error
            (binding [*out* *err*]
              (println "Snapshot channel send failed:" (.getMessage error)))))))))

(defn deliver-effects!
  ([connections effects]
   (deliver-effects! connections nil effects))
  ([connections publisher effects]
   (doseq [{:keys [connection-token connection-tokens message] :as effect} effects]
     (if connection-tokens
       (when publisher
         (publication/publish! publisher effect))
       (when-let [channel (get @connections connection-token)]
         (http/send! channel (pr-str message)))))))

(defn websocket-response [request room-agent connections]
  (if (:websocket? request)
    (let [connection-token (Object.)]
      (http/as-channel request
                       {:on-open (fn [channel]
                                   (swap! connections assoc connection-token channel)
                                   (http/send! channel (pr-str {:type :ready}))
                                   (room/enqueue-room-message!
                                    room-agent {:type :join
                                                :connection-token connection-token}))
                        :on-receive (fn [channel message]
                                      (let [{:keys [input reply]}
                                            (protocol/read-client-message message)]
                                        (if input
                                          (room/enqueue-room-message!
                                           room-agent {:type :input
                                                       :connection-token connection-token
                                                       :input input})
                                          (http/send! channel (pr-str reply)))))
                        :on-close (fn [_channel _status]
                                    (swap! connections dissoc connection-token))}))
    {:status 426
     :headers {"Content-Type" "text/plain; charset=utf-8"
               "Upgrade" "websocket"}
     :body "WebSocket connection required\n"}))

(defn routes [{:keys [request-method uri]}]
  (if (and (= :get request-method) (= "/health" uri))
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "ok\n"}
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Not found\n"}))

(defn make-handler [public-dir]
  (let [connections (atom {})
        publisher (atom nil)
        room-agent (room/make-room #(deliver-effects! connections @publisher %))
        static-handler (-> routes
                           (wrap-file public-dir)
                           wrap-content-type
                           wrap-head)]
    (with-meta
      (fn [request]
        (if (and (= :get (:request-method request)) (= "/ws" (:uri request)))
          (websocket-response request room-agent connections)
          (static-handler request)))
      {::room-agent room-agent
       ::connections connections
       ::publisher publisher})))

(defn parse-options [args]
  (let [[host port] args
        host (or host (:host default-options))
        port (if port (parse-long port) (:port default-options))]
    (when (or (> (count args) 2)
              (str/blank? host)
              (nil? port)
              (not (<= 1 port 65535)))
      (throw (ex-info "Usage: clojure -M:server [host] [port: 1-65535]"
                      {:args args})))
    {:host host :port port}))

(defn start! [{:keys [host port public-dir]
               :or {host "127.0.0.1" port 8082 public-dir "public"}}]
  (let [handler (make-handler public-dir)
        server (http/run-server handler
                                {:ip host :port port :legacy-return-value? false})]
    (try
      (let [publisher (publication/start! (partial broadcast-world!
                                                  (::connections (meta handler))))]
        (reset! (::publisher (meta handler)) publisher)
        (try
          (swap! runtimes assoc server
                 {:ticker (ticker/start! (::room-agent (meta handler)))
                  :publisher publisher})
          server
          (catch Exception error
            (publication/stop! publisher)
            (throw error))))
      (catch Exception error
        (when-let [stopped (http/server-stop! server {:timeout 1000})]
          @stopped)
        (throw error)))))

(defn stop! [server]
  (locking server
    (let [{:keys [ticker publisher]} (get @runtimes server)]
      (try
        (ticker/stop! ticker)
        (finally
          (try
            (publication/stop! publisher)
            (finally
              (swap! runtimes dissoc server)
              (when-let [stopped (http/server-stop! server {:timeout 1000})]
                @stopped)))))))
  nil)

(defn -main [& args]
  (let [{:keys [host] :as options} (parse-options args)
        server (start! options)
        shutdown-hook (Thread. #(stop! server))]
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    (try
      (println (str "Clojure Arena HTTP server: http://" host ":"
                    (http/server-port server)))
      (http/server-join server)
      (finally
        (stop! server)
        (try
          (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
          (catch IllegalStateException _ nil))))))

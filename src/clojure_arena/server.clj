(ns clojure-arena.server
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.head :refer [wrap-head]]))

(def default-options {:host "127.0.0.1" :port 8082})

(defn routes [{:keys [request-method uri]}]
  (if (and (= :get request-method) (= "/health" uri))
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "ok\n"}
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Not found\n"}))

(defn make-handler [public-dir]
  (-> routes
      (wrap-file public-dir)
      wrap-content-type
      wrap-head))

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
  (http/run-server (make-handler public-dir)
                   {:ip host :port port :legacy-return-value? false}))

(defn stop! [server]
  (when-let [stopped (http/server-stop! server {:timeout 1000})]
    @stopped)
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

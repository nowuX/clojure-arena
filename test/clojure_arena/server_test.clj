(ns clojure-arena.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-arena.server :as server]
            [org.httpkit.server :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files FileVisitOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]
           [java.util Arrays]))

(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 2))
      (.build)))

(defn- write-bytes! [^Path path ^bytes contents]
  (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
  (Files/write path contents (make-array OpenOption 0)))

(defn- delete-tree! [^Path root]
  (with-open [paths (Files/walk root (make-array FileVisitOption 0))]
    (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
      (Files/deleteIfExists path))))

(defn- with-temp-public [f]
  (let [root (Files/createTempDirectory "clojure-arena-server-test-"
                                        (make-array FileAttribute 0))
        public-dir (.resolve root "public")
        index-bytes (.getBytes "<!doctype html><title>Fixture</title>\n"
                               StandardCharsets/UTF_8)
        js-bytes (.getBytes "window.__serverFixture = true;\n"
                            StandardCharsets/UTF_8)]
    (try
      (write-bytes! (.resolve public-dir "index.html") index-bytes)
      (write-bytes! (.resolve public-dir "js/main.js") js-bytes)
      (write-bytes! (.resolve root ".git/config")
                    (.getBytes "[remote \"origin\"]\n" StandardCharsets/UTF_8))
      (write-bytes! (.resolve root "README.md")
                    (.getBytes "outside the public root\n" StandardCharsets/UTF_8))
      (f {:root root
          :public-dir (str public-dir)
          :index-bytes index-bytes
          :js-bytes js-bytes})
      (finally
        (delete-tree! root)))))

(defn- request [port method path]
  (let [uri (URI. (str "http://127.0.0.1:" port path))
        http-request (-> (HttpRequest/newBuilder uri)
                         (.timeout (Duration/ofSeconds 4))
                         (.method method (HttpRequest$BodyPublishers/noBody))
                         (.build))]
    (.send client http-request (HttpResponse$BodyHandlers/ofByteArray))))

(defn- content-type [response]
  (-> response .headers (.firstValue "Content-Type") (.orElse "")))

(defn- bytes-equal? [^bytes expected ^bytes actual]
  (Arrays/equals expected actual))

(defn- body-bytes [body]
  (if (instance? File body)
    (Files/readAllBytes (.toPath ^File body))
    ^bytes body))

(deftest routes-test
  (testing "the health route is a plain UTF-8 GET response"
    (is (= {:status 200
            :headers {"Content-Type" "text/plain; charset=utf-8"}
            :body "ok\n"}
           (server/routes {:request-method :get :uri "/health"}))))
  (testing "other methods and paths are not routes"
    (doseq [request-map [{:request-method :head :uri "/health"}
                         {:request-method :post :uri "/health"}
                         {:request-method :get :uri "/missing"}]]
      (is (= 404 (:status (server/routes request-map)))))))

(deftest parse-options-test
  (testing "defaults and valid command-line options"
    (is (= {:host "127.0.0.1" :port 8082} (server/parse-options [])))
    (is (= {:host "0.0.0.0" :port 9000}
           (server/parse-options ["0.0.0.0" "9000"])))
    (is (= {:host "127.0.0.1" :port 1}
           (server/parse-options [nil "1"])))
    (is (= {:host "127.0.0.1" :port 65535}
           (server/parse-options ["127.0.0.1" "65535"]))))
  (testing "invalid argument counts, hosts, and ports are rejected"
    (doseq [args [["host" "8082" "extra"]
                  ["   " "8082"]
                  ["host" "not-a-number"]
                  ["host" "0"]
                  ["host" "-1"]
                  ["host" "65536"]]]
      (is (thrown? Exception (server/parse-options args))
          (str "expected invalid options to fail: " (pr-str args))))))

(deftest make-handler-static-files-test
  (with-temp-public
    (fn [{:keys [public-dir index-bytes js-bytes]}]
      (let [handler (server/make-handler public-dir)]
        (testing "GET serves the index and existing JavaScript file byte-for-byte"
          (let [index-response (handler {:request-method :get :uri "/"})
                js-response (handler {:request-method :get :uri "/js/main.js"})]
            (is (= 200 (:status index-response)))
            (is (bytes-equal? index-bytes (body-bytes (:body index-response))))
            (is (= 200 (:status js-response)))
            (is (bytes-equal? js-bytes (body-bytes (:body js-response))))
            (is (= "text/javascript"
                   (get-in js-response [:headers "Content-Type"])))))
        (testing "HEAD uses the GET resource metadata but omits the body"
          (let [head-response (handler {:request-method :head
                                        :uri "/js/main.js"})]
            (is (= 200 (:status head-response)))
            (is (= "text/javascript"
                   (get-in head-response [:headers "Content-Type"])))
            (is (nil? (:body head-response)))))
        (testing "missing files and unsupported methods return 404"
          (is (= 404 (:status (handler {:request-method :get
                                        :uri "/missing.js"}))))
          (is (= 404 (:status (handler {:request-method :post
                                        :uri "/index.html"}))))
          (is (= 404 (:status (handler {:request-method :head
                                        :uri "/missing.js"})))))))))

(deftest static-files-stay-within-public-root-test
  (with-temp-public
    (fn [{:keys [public-dir]}]
      (let [handler (server/make-handler public-dir)]
        (doseq [path ["/.git/config"
                      "/README.md"
                      "/%2e%2e/README.md"
                      "/%2E%2E/.git/config"
                      "/js/%2e%2e/%2e%2e/README.md"
                      "/%2e%2e%2fREADME.md"]]
          (is (= 404 (:status (handler {:request-method :get :uri path})))
              (str "must not serve outside the public root: " path)))))))

(deftest real-http-and-stop-rebind-test
  (with-temp-public
    (fn [{:keys [public-dir index-bytes js-bytes]}]
      (let [first-server (server/start! {:host "127.0.0.1"
                                         :port 0
                                         :public-dir public-dir})
            port (http/server-port first-server)]
        (try
          (is (pos? port) "port 0 should bind an ephemeral loopback port")
          (testing "real loopback requests expose health and static resources"
            (let [health (request port "GET" "/health")
                  index (request port "GET" "/")
                  js (request port "GET" "/js/main.js")
                  head (request port "HEAD" "/js/main.js")
                  missing (request port "GET" "/missing.js")
                  method (request port "POST" "/index.html")]
              (is (= 200 (.statusCode health)))
              (is (= "ok\n" (String. ^bytes (.body health)
                                      StandardCharsets/UTF_8)))
              (is (= "text/plain; charset=utf-8" (content-type health)))
              (is (= 200 (.statusCode index)))
              (is (bytes-equal? index-bytes (.body index)))
              (is (str/starts-with? (content-type index) "text/html"))
              (is (= 200 (.statusCode js)))
              (is (bytes-equal? js-bytes (.body js)))
              (is (= "text/javascript" (content-type js)))
              (is (= 200 (.statusCode head)))
              (is (= "text/javascript" (content-type head)))
              (is (zero? (alength ^bytes (.body head))))
              (is (= 404 (.statusCode missing)))
              (is (= 404 (.statusCode method))))
            (doseq [path ["/.git/config"
                          "/README.md"
                          "/%2e%2e/README.md"
                          "/%2E%2E/.git/config"
                          "/js/%2e%2e/%2e%2e/README.md"
                          "/%2e%2e%2fREADME.md"]]
              (is (= 404 (.statusCode (request port "GET" path)))
                  (str "must not serve outside the public root: " path))))
          (finally
            (server/stop! first-server)))
        (testing "stop waits for shutdown and the same port can be rebound"
          (let [second-server (server/start! {:host "127.0.0.1"
                                              :port port
                                              :public-dir public-dir})]
            (try
              (is (= port (http/server-port second-server)))
              (let [health (request port "GET" "/health")]
                (is (= 200 (.statusCode health)))
                (is (= "ok\n" (String. ^bytes (.body health)
                                        StandardCharsets/UTF_8))))
              (finally
                (server/stop! second-server)
                (server/stop! second-server)))))))))

(defn -main [& _]
  (let [namespaces (->> (file-seq (io/file "test/clojure_arena"))
                        (filter #(re-find #"_test\.cljc?$" (.getName %)))
                        (map #(symbol (str "clojure-arena."
                                           (-> (.getName %)
                                               (str/replace #"\.cljc?$" "")
                                               (str/replace "_" "-")))))
                        sort)
        {:keys [fail error]}
        (try
          (doseq [test-ns namespaces] (require test-ns))
          (apply run-tests namespaces)
          (finally (shutdown-agents)))]
    (when (pos? (+ fail error))
      (System/exit 1))))

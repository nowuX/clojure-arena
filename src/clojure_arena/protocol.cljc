(ns clojure-arena.protocol
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.tools.reader.edn :as edn])
            #?(:cljs [cljs.tools.reader.reader-types :as readers])))

(def error-codes #{:invalid-edn :invalid-message :unsupported-type})

(defn decode [text]
  (let [reader #?(:clj (java.io.PushbackReader. (java.io.StringReader. text))
                  :cljs (readers/string-push-back-reader text))
        eof #?(:clj (Object.) :cljs (js-obj))
        message (edn/read {:eof eof} reader)]
    (when (or (identical? eof message)
              (not (identical? eof (edn/read {:eof eof} reader))))
      (throw (ex-info "Expected one EDN value" {})))
    message))

(defn reply-to-message [text]
  (if-not (string? text)
    {:type :error :code :invalid-message}
    (try
      (let [message (decode text)]
        (cond
          (not (and (map? message) (keyword? (:type message))))
          {:type :error :code :invalid-message}

          (not= :ping (:type message))
          {:type :error :code :unsupported-type}

          (not= {:type :ping} message)
          {:type :error :code :invalid-message}

          :else {:type :pong}))
      (catch #?(:clj Exception :cljs :default) _
        {:type :error :code :invalid-edn}))))

(defn server-message? [message]
  (or (= {:type :ready} message)
      (= {:type :pong} message)
      (and (map? message)
           (= #{:type :code} (set (keys message)))
           (= :error (:type message))
           (contains? error-codes (:code message)))))

(ns clojure-arena.protocol
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.tools.reader.edn :as edn])
            #?(:cljs [cljs.tools.reader.reader-types :as readers])))

(def error-codes #{:invalid-edn :invalid-message :unsupported-type})
(def player-colors [:cyan :crimson :amber :green])
(def neutral-input {:horizontal 0 :jump? false :drop? false :attack? false})

(defn player-input? [input]
  (and (map? input)
       (= #{:horizontal :jump? :drop? :attack?} (set (keys input)))
       (contains? #{-1 0 1} (:horizontal input))
       (every? boolean? (map input [:jump? :drop? :attack?]))))

(defn merge-pending-input [pending input]
  {:horizontal (:horizontal input)
   :jump? (or (:jump? pending) (:jump? input))
   :drop? (or (:drop? pending) (:drop? input))
   :attack? (or (:attack? pending) (:attack? input))})

(defn decode [text]
  (let [reader #?(:clj (java.io.PushbackReader. (java.io.StringReader. text))
                  :cljs (readers/string-push-back-reader text))
        eof #?(:clj (Object.) :cljs (js-obj))
        message (edn/read {:eof eof} reader)]
    (when (or (identical? eof message)
              (not (identical? eof (edn/read {:eof eof} reader))))
      (throw (ex-info "Expected one EDN value" {})))
    message))

(defn read-client-message [text]
  (if-not (string? text)
    {:reply {:type :error :code :invalid-message}}
    (try
      (let [message (decode text)]
        (cond
          (not (and (map? message) (keyword? (:type message))))
          {:reply {:type :error :code :invalid-message}}

          (= :input (:type message))
          (let [input (dissoc message :type)]
            (if (player-input? input)
              {:input input}
              {:reply {:type :error :code :invalid-message}}))

          (not= :ping (:type message))
          {:reply {:type :error :code :unsupported-type}}

          (not= {:type :ping} message)
          {:reply {:type :error :code :invalid-message}}

          :else {:reply {:type :pong}}))
      (catch #?(:clj Exception :cljs :default) _
        {:reply {:type :error :code :invalid-edn}}))))

(defn reply-to-message [text]
  (:reply (read-client-message text)))

(defn server-message? [message]
  (or (= {:type :ready} message)
      (= {:type :pong} message)
      (= {:type :room-full} message)
      (and (map? message)
           (= #{:type :player-id :color} (set (keys message)))
           (= :identity (:type message))
           (keyword? (:player-id message))
           (nil? (namespace (:player-id message)))
           (re-matches #"player-[1-9][0-9]*" (name (:player-id message)))
           (contains? (set player-colors) (:color message)))
      (and (map? message)
           (= #{:type :code} (set (keys message)))
           (= :error (:type message))
           (contains? error-codes (:code message)))))

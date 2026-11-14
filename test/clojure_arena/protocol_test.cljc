(ns clojure-arena.protocol-test
  (:require [clojure-arena.protocol :as protocol]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(defn- throws? [f]
  (try
    (f)
    false
    (catch #?(:clj Exception :cljs :default) _
      true)))

(deftest decode-exactly-one-edn-value-test
  (testing "decode reads one EDN value and allows trailing whitespace"
    (is (= {:type :ping} (protocol/decode "{:type :ping}  \n\t")))
    (is (= [1 :two nil] (protocol/decode "[1 :two nil]")))
    (is (nil? (protocol/decode "nil"))))
  (testing "empty, malformed, or multiple values are rejected"
    (doseq [text ["" "  \n" "{:type" "{} {}" "1 trailing"]]
      (is (throws? #(protocol/decode text))
          (str "expected exactly one EDN value for " (pr-str text))))))

(deftest reply-to-message-test
  (testing "an exact ping returns pong"
    (is (= {:type :pong}
           (protocol/reply-to-message "{:type :ping}"))))
  (testing "invalid EDN and invalid message shapes have distinct errors"
    (doseq [text ["{" "{:type :ping} {:type :ping}"]]
      (is (= {:type :error :code :invalid-edn}
             (protocol/reply-to-message text))))
    (doseq [text [nil 42 :ping "[]" "{}" "{:type \"ping\"}"
                  "{:type :ping :nonce 1}"]]
      (is (= {:type :error :code :invalid-message}
             (protocol/reply-to-message text))
          (str "expected invalid-message for " (pr-str text)))))
  (testing "well-formed but unsupported keyword types remain unsupported"
    (doseq [message [:join :tick :leave :unknown]]
      (is (= {:type :error :code :unsupported-type}
             (protocol/reply-to-message (pr-str {:type message})))
          (str "expected unsupported-type for " message)))))

(deftest server-message-validation-and-roundtrip-test
  (testing "server messages accept only the documented exact maps"
    (is (= [:cyan :crimson :amber :green] protocol/player-colors))
    (doseq [message [{:type :ready}
                     {:type :pong}
                     {:type :identity :player-id :player-1 :color :cyan}
                     {:type :identity :player-id :player-24 :color :green}
                     {:type :room-full}
                     {:type :error :code :invalid-edn}
                     {:type :error :code :invalid-message}
                     {:type :error :code :unsupported-type}]]
      (is (protocol/server-message? message)
          (str "expected valid server message: " (pr-str message))))
    (doseq [message [{:type :ready :extra true}
                     {:type :pong :extra true}
                     {:type :identity :player-id :player-1 :color :cyan
                      :extra true}
                     {:type :identity :player-id "player-1" :color :cyan}
                     {:type :identity :player-id :other :color :cyan}
                     {:type :identity :player-id :player-0 :color :cyan}
                     {:type :identity :player-id :arena/player-1 :color :cyan}
                     {:type :identity :player-id :player-1 :color :unknown}
                     {:type :identity :player-id :player-1}
                     {:type :identity :player-id :player-1 :color :cyan
                      :code :invalid-message}
                     {:type :room-full :extra true}
                     {:type :room-full :player-id :player-5}
                     {:type :error :code :unknown}
                     {:type :error :code :invalid-edn :extra true}
                     {:type :error}
                     "{:type :pong}"]]
      (is (not (protocol/server-message? message))
          (str "expected invalid server message: " (pr-str message)))))
  (testing "replies survive EDN wire encoding and decode as valid server messages"
    (doseq [[text expected] [["{:type :ping}" {:type :pong}]
                             ["{" {:type :error :code :invalid-edn}]
                             ["[]" {:type :error :code :invalid-message}]
                             ["{:type :join}" {:type :error
                                               :code :unsupported-type}]]]
      (let [reply (protocol/reply-to-message text)
            decoded (protocol/decode (pr-str reply))]
        (is (= expected reply))
        (is (= reply decoded))
        (is (protocol/server-message? decoded)))))
  (testing "admission messages survive EDN wire encoding and decode exactly"
    (doseq [message [{:type :identity :player-id :player-1 :color :cyan}
                     {:type :identity :player-id :player-4 :color :green}
                     {:type :room-full}]]
      (let [decoded (protocol/decode (pr-str message))]
        (is (= message decoded))
        (is (protocol/server-message? decoded))))))

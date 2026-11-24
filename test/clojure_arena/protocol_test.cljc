(ns clojure-arena.protocol-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(defn- throws? [f]
  (try
    (f)
    false
    (catch #?(:clj Exception :cljs :default) _
      true)))

(deftest connection-timeout-contract-test
  (let [message {:type :error :code :connection-timeout}]
    (is (protocol/server-message? message))
    (is (= message (protocol/decode (pr-str message))))
    (doseq [invalid [(assoc message :player-id :player-1)
                     (dissoc message :type) (dissoc message :code)]]
      (is (not (protocol/server-message? invalid))))
    (is (= {:reply {:type :error :code :unsupported-type}}
           (protocol/read-client-message (pr-str message))))))

(deftest player-input-validation-test
  (testing "neutral input has the exact documented shape"
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false
            :dash? false :antiknockback? false :recover? false :grenade? false :nova? false}
           protocol/neutral-input)))
  (testing "horizontal input accepts only the three discrete directions"
    (doseq [horizontal [-1 0 1]]
      (is (protocol/player-input? (assoc protocol/neutral-input :horizontal horizontal))
          (str "expected valid horizontal value " horizontal)))
    (doseq [horizontal [nil -2 2 0.5 "1" :left]]
      (is (not (protocol/player-input? (assoc protocol/neutral-input :horizontal horizontal)))
          (str "expected invalid horizontal value " (pr-str horizontal)))))
  (testing "all action fields must be booleans and the key set must be exact"
    (doseq [key protocol/action-keys
            value [false true]]
      (is (protocol/player-input? (assoc protocol/neutral-input key value))))
    (doseq [key protocol/action-keys
            value [nil 0 1 "true" :true]]
      (is (not (protocol/player-input? (assoc protocol/neutral-input key value)))
          (str "expected invalid " key " value " (pr-str value))))
    (doseq [input [(dissoc protocol/neutral-input :attack?)
                   (dissoc protocol/neutral-input :dash?)
                   (dissoc protocol/neutral-input :nova?)
                   {:horizontal 0 :jump? false :drop? false :attack? false}
                   (assoc protocol/neutral-input :extra true)
                   (assoc protocol/neutral-input :player-id :player-1)
                   (assoc protocol/neutral-input :connection-token :forged)
                   (assoc protocol/neutral-input :position [1 2])
                   (assoc protocol/neutral-input :velocity [3 4])
                   [] nil]]
      (is (not (protocol/player-input? input))
          (str "expected invalid input payload " (pr-str input))))))

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

(deftest read-client-message-input-and-error-shapes-test
  (testing "an exact flat input frame returns its payload without a reply"
    (doseq [horizontal [-1 0 1]
            jump? [false true]
            drop? [false true]
            attack? [false true]]
      (let [payload (assoc protocol/neutral-input :horizontal horizontal :jump? jump? :drop? drop? :attack? attack?)
            frame (pr-str (assoc payload :type :input))]
        (is (= {:input payload}
               (protocol/read-client-message frame))
            (str "unexpected decoded input for " frame))
        (is (nil? (protocol/reply-to-message frame))
            "the compatibility wrapper should not reply to valid input")))
    (is (= {:reply {:type :pong}}
           (protocol/read-client-message "{:type :ping}")))
    (is (= {:type :pong}
           (protocol/reply-to-message "{:type :ping}"))))
  (testing "invalid EDN and invalid message shapes retain distinct errors"
    (doseq [text ["{" "{:type :input :horizontal 0" "{} {}"]]
      (is (= {:reply {:type :error :code :invalid-edn}}
             (protocol/read-client-message text))))
    (doseq [text [nil 42 :input "[]" "{}" "{:type \"input\"}"
                  "{:type :ping :nonce 1}"]]
      (is (= {:reply {:type :error :code :invalid-message}}
             (protocol/read-client-message text))
          (str "expected invalid-message for " (pr-str text)))
      (is (= {:type :error :code :invalid-message}
             (protocol/reply-to-message text)))))
  (testing "malformed or forged input payloads are rejected"
    (let [valid (assoc protocol/neutral-input :type :input :horizontal 1 :jump? true)
          forged [(assoc valid :player-id :player-99)
                  (assoc valid :connection-token :forged)
                  (assoc valid :position [999 999])
                  (assoc valid :velocity [50 50])
                  (assoc valid :extra true)
                  (assoc valid :horizontal 2)
                  (assoc valid :jump? 1)
                  (dissoc valid :attack?)]]
      (doseq [message forged]
        (let [frame (pr-str message)]
          (is (= {:reply {:type :error :code :invalid-message}}
                 (protocol/read-client-message frame))
              (str "expected forged or malformed input to be rejected: " frame))
          (is (= {:type :error :code :invalid-message}
                 (protocol/reply-to-message frame)))))))
  (testing "unsupported keyword message types preserve their error code"
    (doseq [message [:join :tick :leave :unknown]]
      (let [frame (pr-str {:type message})]
        (is (= {:reply {:type :error :code :unsupported-type}}
               (protocol/read-client-message frame)))
        (is (= {:type :error :code :unsupported-type}
               (protocol/reply-to-message frame)))))))

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

(deftest snapshot-message-validation-test
  (let [snapshot {:type :snapshot :tick 0 :world (match-helpers/running-world game/initial-world)}
        player-id :player-1
        infinity #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity)
        invalid-messages
        [(assoc snapshot :room {:members {}})
         (assoc snapshot :tick nil)
         (assoc snapshot :tick -1)
         (assoc snapshot :tick 1.5)
         (assoc snapshot :tick (inc protocol/safe-integer-maximum))
         (assoc snapshot :world nil)
         (assoc-in snapshot [:world :members] {})
         (assoc-in snapshot [:world :players] nil)
         (assoc-in snapshot [:world :platforms] nil)
         (assoc-in snapshot [:world :platforms] (seq (:platforms game/initial-world)))
         (update-in snapshot [:world :platforms 0] dissoc :height)
         (update-in snapshot [:world :platforms 0] assoc :extra true)
         (update-in snapshot [:world :platforms 0] assoc :x infinity)
         (update-in snapshot [:world :platforms 0] assoc :width 0)
         (update-in snapshot [:world :platforms 0] assoc :color nil)
         (update-in snapshot [:world :players player-id] dissoc :radius)
         (update-in snapshot [:world :players player-id] assoc :extra true)
         (update-in snapshot [:world :players player-id] assoc :x infinity)
         (update-in snapshot [:world :players player-id] assoc :vy nil)
         (update-in snapshot [:world :players player-id] assoc :radius 0)
         (update-in snapshot [:world :players player-id] assoc :spawn-locked? nil)
         (update-in snapshot [:world :players player-id] assoc :air-jumps-left -1)
         (update-in snapshot [:world :players player-id]
                    assoc :drop-through-platform-id :main)
         (update-in snapshot [:world :players player-id] assoc :facing :up)
         (update-in snapshot [:world :players player-id] assoc :color "url(javascript:bad)")
         (update-in snapshot [:world :players player-id] dissoc :dash-cooldown-left)
         (update-in snapshot [:world :players player-id] assoc :dash-ticks-left 9)
         (update-in snapshot [:world :players player-id] assoc :dash-ticks-left -1)
         (update-in snapshot [:world :players player-id] assoc :dash-ticks-left 2.5)
         (update-in snapshot [:world :players player-id] assoc :dash-cooldown-left -0.1)
         (update-in snapshot [:world :players player-id] assoc :dash-cooldown-left 2.6)
         (update-in snapshot [:world :players player-id] assoc :dash-cooldown-left infinity)
         (update-in snapshot [:world :players player-id] assoc :dash-stopped? nil)
         (update-in snapshot [:world :players player-id] assoc :dash-stopped? true)
         (update-in snapshot [:world :players player-id]
                    assoc :dash-ticks-left 3 :lifecycle :respawning :respawn-seconds-left 2)
         (update-in snapshot [:world :players player-id] dissoc :antiknockback-buffered?)
         (update-in snapshot [:world :players player-id] assoc :antiknockback-seconds-left 5.1)
         (update-in snapshot [:world :players player-id] assoc :antiknockback-seconds-left -1)
         (update-in snapshot [:world :players player-id] assoc :antiknockback-cooldown-left 10.5)
         (update-in snapshot [:world :players player-id] assoc :antiknockback-cooldown-left nil)
         (update-in snapshot [:world :players player-id] assoc :antiknockback-buffered? true)
         (update-in snapshot [:world :players player-id]
                    assoc :antiknockback-seconds-left 2 :lifecycle :respawning :respawn-seconds-left 2)
         (update-in snapshot [:world :players player-id] dissoc :recover-cooldown-left)
         (update-in snapshot [:world :players player-id] assoc :recover-cooldown-left 12.5)
         (update-in snapshot [:world :players player-id] assoc :recover-cooldown-left -1)
         (update-in snapshot [:world :players player-id] assoc :recover-buffered? nil)
         (update-in snapshot [:world :players player-id] assoc :recover-buffered? true)
         (assoc-in snapshot [:world :players "player-1"]
                   (get-in snapshot [:world :players player-id]))]]
    (testing "the current game factory scene is a complete snapshot"
      (is (protocol/snapshot-message? snapshot))
      (is (protocol/server-message? snapshot))
      (is (protocol/snapshot-message?
           (assoc snapshot :tick protocol/safe-integer-maximum)))
      (is (protocol/snapshot-message?
           (assoc-in snapshot [:world :players player-id
                               :drop-through-platform-id] :left)))
      (is (protocol/snapshot-message?
           (update-in snapshot [:world :players player-id]
                      assoc :dash-ticks-left 8 :dash-cooldown-left 2.5 :dash-stopped? true))
          "an active dash at its bounds, stopped by a wall, is a valid state")
      (is (protocol/snapshot-message?
           (update-in snapshot [:world :players player-id]
                      assoc :antiknockback-seconds-left 5 :antiknockback-cooldown-left 10
                      :dash-ticks-left 4 :antiknockback-buffered? true))
          "an active Q at its bounds and a Q buffered during a dash are valid")
      (is (protocol/snapshot-message?
           (-> snapshot
               (update-in [:world :players player-id]
                          assoc :recover-cooldown-left 12 :dash-ticks-left 2 :recover-buffered? true
                          :accumulated-percentage 56.00000000000001)
               (assoc-in [:world :match :contenders player-id :accumulated-percentage]
                         56.00000000000001)))
          "a full E cooldown, an E buffered during a dash and fractional percentages are valid")
      (is (nil? (:drop-through-platform-id
                 (get-in snapshot [:world :players player-id])))
          "the valid nullable platform state remains present in factory players"))
    (testing "unknown fields, missing fields, nils, and unsafe values are rejected"
      (doseq [message invalid-messages]
        (is (not (protocol/snapshot-message? message))
            (str "expected invalid snapshot: " (pr-str message)))
        (is (not (protocol/server-message? message))
            (str "expected invalid server snapshot: " (pr-str message)))))))

(deftest ability-intentions-coalesce-like-other-one-shot-actions-test
  (testing "pending ability flags survive later frames until a tick consumes them"
    (let [dash (assoc protocol/neutral-input :horizontal 1 :dash? true)
          grenade (assoc protocol/neutral-input :horizontal -1 :grenade? true)]
      (is (= (assoc protocol/neutral-input :horizontal -1 :dash? true :grenade? true)
             (-> protocol/neutral-input
                 (protocol/merge-pending-input dash)
                 (protocol/merge-pending-input grenade))))
      (is (= (assoc protocol/neutral-input :dash? true :grenade? true)
             (-> protocol/neutral-input
                 (protocol/merge-pending-input dash)
                 (protocol/merge-pending-input grenade)
                 (protocol/merge-pending-input protocol/neutral-input)))
          "a neutral release clears direction but keeps captured intentions")
      (is (protocol/player-input?
           (protocol/merge-pending-input protocol/neutral-input dash))
          "merging preserves the exact input shape")))
  (testing "the wire contract requires every ability flag and nothing more"
    (doseq [action protocol/ability-action-keys]
      (let [payload (assoc protocol/neutral-input action true)]
        (is (= {:input payload}
               (protocol/read-client-message (pr-str (assoc payload :type :input)))))))
    (doseq [frame ["{:type :input :horizontal 0 :jump? false :drop? false :attack? false}"
                   (pr-str (assoc (dissoc protocol/neutral-input :recover?) :type :input))
                   (pr-str (assoc protocol/neutral-input :type :input :dash? 1))
                   (pr-str (assoc protocol/neutral-input :type :input :dash-speed 400))
                   (pr-str (assoc protocol/neutral-input :type :input :nova-charge 100))]]
      (is (= {:reply {:type :error :code :invalid-message}}
             (protocol/read-client-message frame))
          (str "expected invalid-message for " frame)))))

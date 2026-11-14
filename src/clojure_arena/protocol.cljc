(ns clojure-arena.protocol
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.tools.reader.edn :as edn])
            #?(:cljs [cljs.tools.reader.reader-types :as readers])))

(def error-codes #{:invalid-edn :invalid-message :unsupported-type :connection-timeout})
(def player-colors [:cyan :crimson :amber :green])
(def neutral-input {:horizontal 0 :jump? false :drop? false :attack? false})
(def safe-integer-maximum 9007199254740991)

(def platform-fields #{:id :type :x :y :width :height :color})
(def player-fields #{:stocks :lifecycle :x :y :vx :vy :knockback-vx :knockback-vy
                     :facing :spawn-locked? :air-jumps-left
                     :drop-through-platform-id :melee-cooldown-left
                     :melee-strike-id :melee-resolved-strike-id
                     :melee-target-ids :accumulated-percentage :radius :color})

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

(defn- safe-nonnegative-integer? [value]
  #?(:clj (and (integer? value)
               (<= 0 value safe-integer-maximum))
     :cljs (and (number? value)
                (js/Number.isSafeInteger value)
                (<= 0 value safe-integer-maximum))))

(defn- finite-number? [value]
  (and (number? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn- player-id? [value]
  (and (keyword? value)
       (nil? (namespace value))
       (re-matches #"player-[1-9][0-9]*" (name value))))

(defn- platform-id? [value]
  (and (keyword? value)
       (nil? (namespace value))))

(defn- color? [value]
  (and (string? value)
       (re-matches #"#[0-9A-Fa-f]{6}" value)))

(defn- platform? [platform]
  (and (map? platform)
       (= platform-fields (set (keys platform)))
       (platform-id? (:id platform))
       (contains? #{:solid :semisolid} (:type platform))
       (every? finite-number? (map platform [:x :y :width :height]))
       (pos? (:width platform))
       (pos? (:height platform))
       (color? (:color platform))))

(defn- blast-bounds? [bounds]
  (and (map? bounds)
       (= #{:left :right :top :bottom} (set (keys bounds)))
       (every? finite-number? (vals bounds))
       (< (:left bounds) (:right bounds))
       (< (:top bounds) (:bottom bounds))))

(defn- stock-state? [{:keys [stocks lifecycle]}]
  (and (safe-nonnegative-integer? stocks)
       (<= stocks 3)
       (case lifecycle
         :active (pos? stocks)
         :respawning (pos? stocks)
         :eliminated (zero? stocks)
         false)))

(defn- player? [player semisolid-platform-ids]
  (let [drop-through-id (:drop-through-platform-id player)]
    (and (map? player)
         (= player-fields (set (keys player)))
         (stock-state? player)
         (every? finite-number?
                 (map player [:x :y :vx :vy :knockback-vx :knockback-vy
                              :melee-cooldown-left :accumulated-percentage
                              :radius]))
         (pos? (:radius player))
         (not (neg? (:melee-cooldown-left player)))
         (not (neg? (:accumulated-percentage player)))
         (safe-nonnegative-integer? (:air-jumps-left player))
         (safe-nonnegative-integer? (:melee-strike-id player))
         (safe-nonnegative-integer? (:melee-resolved-strike-id player))
         (contains? #{:left :right} (:facing player))
         (boolean? (:spawn-locked? player))
         (or (nil? drop-through-id)
             (contains? semisolid-platform-ids drop-through-id))
         (set? (:melee-target-ids player))
         (every? player-id? (:melee-target-ids player))
         (color? (:color player)))))

(defn snapshot-message? [message]
  (and (map? message)
       (= #{:type :tick :world} (set (keys message)))
       (= :snapshot (:type message))
       (safe-nonnegative-integer? (:tick message))
       (let [world (:world message)
             platforms (when (map? world) (:platforms world))
             players (when (map? world) (:players world))
             platform-ids (when (vector? platforms)
                            (set (map :id platforms)))
             semisolid-platform-ids (when (vector? platforms)
                                      (set (map :id
                                                (filter #(= :semisolid (:type %))
                                                        platforms))))]
         (and (map? world)
              (= #{:blast-bounds :platforms :players} (set (keys world)))
              (blast-bounds? (:blast-bounds world))
              (vector? platforms)
              (every? platform? platforms)
              (= (count platform-ids) (count platforms))
              (map? players)
              (every? player-id? (keys players))
              (every? #(player? % semisolid-platform-ids) (vals players))))))

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
      (snapshot-message? message)
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

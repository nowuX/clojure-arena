(ns clojure-arena.protocol
  (:require [clojure-arena.match :as match]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.tools.reader.edn :as edn])
            #?(:cljs [cljs.tools.reader.reader-types :as readers])))

(def error-codes #{:invalid-edn :invalid-message :unsupported-type :connection-timeout})
(def player-colors [:cyan :crimson :amber :green])
(def ability-action-keys [:dash? :antiknockback? :recover? :grenade? :nova?])
(def action-keys (into [:jump? :drop? :attack?] ability-action-keys))
(def input-keys (set (cons :horizontal action-keys)))
(def neutral-input (assoc (zipmap action-keys (repeat false)) :horizontal 0))
(def safe-integer-maximum 9007199254740991)

(def platform-fields #{:id :type :x :y :width :height :color})
(def player-fields #{:x :y :vx :vy :knockback-vx :knockback-vy
                     :stocks :lifecycle
                     :spawn-x :spawn-y :respawn-seconds-left :protection-seconds-left
                     :facing :spawn-locked? :air-jumps-left
                     :drop-through-platform-id :melee-cooldown-left
                     :melee-strike-id :melee-resolved-strike-id
                     :melee-target-ids :accumulated-percentage :radius :color})

(defn player-input? [input]
  (and (map? input)
       (= input-keys (set (keys input)))
       (contains? #{-1 0 1} (:horizontal input))
       (every? boolean? (map input action-keys))))

(defn merge-pending-input
  "Keeps the latest direction and every one-shot action still waiting for a tick."
  [pending input]
  (into {:horizontal (:horizontal input)}
        (map (fn [action] [action (boolean (or (get pending action) (get input action)))]))
        action-keys))

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

(defn- stock-state? [player]
  (let [stocks (:stocks player)
        respawn (:respawn-seconds-left player)
        protection (:protection-seconds-left player)]
    (and (safe-nonnegative-integer? stocks)
         (<= stocks 3)
         (finite-number? respawn)
         (finite-number? protection)
         (<= 0 respawn 3)
         (<= 0 protection 2)
         (case (:lifecycle player)
           :waiting (and (zero? stocks) (zero? respawn) (zero? protection))
           :active (and (pos? stocks) (zero? respawn))
           :respawning (and (pos? stocks) (pos? respawn) (zero? protection))
           :eliminated (and (zero? stocks) (zero? respawn) (zero? protection))
           false))))

(defn- player? [player semisolid-platform-ids]
  (let [drop-through-id (:drop-through-platform-id player)]
    (and (map? player)
         (= player-fields (set (keys player)))
         (stock-state? player)
         (every? finite-number?
                 (map player [:x :y :spawn-x :spawn-y :vx :vy :knockback-vx :knockback-vy
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

(defn- contender-state? [contender]
  (and (map? contender)
       (= #{:color :stocks :accumulated-percentage :forfeited?} (set (keys contender)))
       (color? (:color contender))
       (safe-nonnegative-integer? (:stocks contender))
       (<= (:stocks contender) 3)
       (finite-number? (:accumulated-percentage contender))
       (not (neg? (:accumulated-percentage contender)))
       (boolean? (:forfeited? contender))))

(defn- roster-matches-players? [contenders players]
  (and (every? (fn [[player-id contender]]
                 (if (:forfeited? contender)
                   (not (contains? players player-id))
                   (let [player (get players player-id)]
                     (and player
                          (not= :waiting (:lifecycle player))
                          (= (select-keys contender [:color :stocks :accumulated-percentage])
                             (select-keys player [:color :stocks :accumulated-percentage]))))))
               contenders)
       (every? (fn [[player-id player]]
                 (or (contains? contenders player-id) (= :waiting (:lifecycle player))))
               players)))

(defn- result-state? [result contenders time-seconds-left]
  (let [standings (:standings result)]
    (and (map? result)
         (= #{:reason :winner-id :winner-color :standings} (set (keys result)))
         (contains? #{:last-contender :no-contenders :time-limit} (:reason result))
         (or (and (nil? (:winner-id result)) (nil? (:winner-color result)))
             (and (player-id? (:winner-id result)) (color? (:winner-color result))))
         (vector? standings)
         (= (count contenders) (count standings))
         (every? (fn [standing]
                   (and (map? standing)
                        (player-id? (:player-id standing))
                        (contender-state? (dissoc standing :player-id))))
                 standings)
         (= (set (keys contenders)) (set (map :player-id standings)))
         (every? (fn [{:keys [player-id forfeited?] :as standing}]
                   (let [current (get contenders player-id)]
                     (and (= (select-keys standing [:color :stocks :accumulated-percentage])
                             (select-keys current [:color :stocks :accumulated-percentage]))
                          (or (not forfeited?) (:forfeited? current)))))
                 standings)
         (let [captured (into {} (map (fn [standing]
                                       [(:player-id standing) (dissoc standing :player-id)]))
                              standings)]
           (= result (match/result-for captured time-seconds-left))))))

(defn- match-state? [match-state players]
  (let [{:keys [id phase countdown-seconds-left time-seconds-left
                intermission-seconds-left result contenders]} match-state]
    (and (map? match-state)
         (= #{:id :phase :countdown-seconds-left :time-seconds-left
              :intermission-seconds-left :result :contenders}
            (set (keys match-state)))
         (safe-nonnegative-integer? id)
         (contains? #{:waiting :countdown :running :finished} phase)
         (finite-number? countdown-seconds-left)
         (<= 0 countdown-seconds-left 3)
         (finite-number? time-seconds-left)
         (<= 0 time-seconds-left match/duration)
         (finite-number? intermission-seconds-left)
         (<= 0 intermission-seconds-left match/intermission-duration)
         (if (= :finished phase)
           (pos? intermission-seconds-left)
           (zero? intermission-seconds-left))
         (map? contenders)
         (every? player-id? (keys contenders))
         (every? contender-state? (vals contenders))
         (case phase
           :waiting (and (zero? countdown-seconds-left) (zero? time-seconds-left)
                         (nil? result) (empty? contenders)
                         (< (count players) 2)
                         (every? #(= :waiting (:lifecycle %)) (vals players)))
           :countdown (and (pos? countdown-seconds-left) (zero? time-seconds-left)
                           (nil? result) (empty? contenders)
                           (<= 2 (count players) 4)
                           (every? #(= :waiting (:lifecycle %)) (vals players)))
           :running (and (pos? id) (zero? countdown-seconds-left)
                         (pos? time-seconds-left) (nil? result)
                         (<= 2 (count contenders) 4)
                         (roster-matches-players? contenders players))
           :finished (and (pos? id) (zero? countdown-seconds-left)
                          (<= 2 (count contenders) 4)
                          (roster-matches-players? contenders players)
                          (result-state? result contenders time-seconds-left))
           false))))

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
              (= #{:blast-bounds :platforms :players :match} (set (keys world)))
              (blast-bounds? (:blast-bounds world))
              (vector? platforms)
              (every? platform? platforms)
              (= (count platform-ids) (count platforms))
              (map? players)
              (<= (count players) 4)
              (every? player-id? (keys players))
              (every? #(player? % semisolid-platform-ids) (vals players))
              (match-state? (:match world) players)))))

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

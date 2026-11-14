(ns clojure-arena.match
  (:require [clojure-arena.game :as game]))

(def countdown-duration 3)

(def initial-match
  {:id 0 :phase :waiting :countdown-seconds-left 0
   :time-seconds-left 0 :intermission-seconds-left 0 :result nil :contenders {}})

(defn contender? [world player-id]
  (let [contender (get-in world [:match :contenders player-id])]
    (and (some? contender) (not (:forfeited? contender)))))

(defn playing? [world player-id]
  (and (= :running (get-in world [:match :phase]))
       (game/active-player? (get-in world [:players player-id]))))

(defn- reconcile-entrants [world]
  (let [phase (get-in world [:match :phase])
        enough? (<= 2 (count (:players world)))]
    (cond
      (and (= :waiting phase) enough?)
      (update world :match assoc
              :phase :countdown :countdown-seconds-left countdown-duration)

      (and (= :countdown phase) (not enough?))
      (update world :match assoc :phase :waiting :countdown-seconds-left 0)

      :else world)))


(defn add-player [world player-id player]
  (-> world
      (assoc-in [:players player-id]
                (assoc player :lifecycle :waiting :stocks 0
                       :respawn-seconds-left 0 :protection-seconds-left 0))
      reconcile-entrants))


(defn remove-player [world player-id]
  (reconcile-entrants (update world :players dissoc player-id)))

(defn- capture-contender [contender player]
  (merge contender (select-keys player [:color :stocks :accumulated-percentage])))

(defn- step-running [world inputs-by-player dt]
  (let [advanced (game/step world inputs-by-player dt)]
    (update-in advanced [:match :contenders]
               (fn [contenders]
                 (reduce-kv (fn [result player-id contender]
                              (if-let [player (get-in advanced [:players player-id])]
                                (assoc result player-id (capture-contender contender player))
                                result))
                            contenders contenders)))))

(defn- start-match [world]
  (let [players (into {}
                      (map (fn [[player-id player]]
                             [player-id (-> (game/make-player (:color player)
                                                             (:spawn-x player)
                                                             (:spawn-y player))
                                            game/grant-spawn-protection)]))
                      (:players world))
        contenders (into {}
                         (map (fn [[player-id player]]
                                [player-id (capture-contender {:forfeited? false} player)]))
                         players)]
    (assoc world
           :players players
           :match {:id (inc (get-in world [:match :id]))
                   :phase :running
                   :countdown-seconds-left 0
                   :time-seconds-left 0
                   :intermission-seconds-left 0
                   :result nil
                   :contenders contenders})))


(defn step [world inputs-by-player dt]
  (case (get-in world [:match :phase])
    :countdown
    (let [world (reconcile-entrants world)]
      (if (= :countdown (get-in world [:match :phase]))
        (let [remaining (- (get-in world [:match :countdown-seconds-left]) dt)]
          (if (<= remaining game/cooldown-epsilon)
            (start-match world)
            (assoc-in world [:match :countdown-seconds-left] remaining)))
        world))
    :running (step-running world inputs-by-player dt)
    world))

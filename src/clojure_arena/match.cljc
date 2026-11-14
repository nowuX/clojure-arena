(ns clojure-arena.match
  (:require [clojure-arena.game :as game]))

(def initial-match
  {:id 0 :phase :waiting :countdown-seconds-left 0
   :time-seconds-left 0 :intermission-seconds-left 0 :result nil :contenders {}})

(defn contender? [world player-id]
  (let [contender (get-in world [:match :contenders player-id])]
    (and (some? contender) (not (:forfeited? contender)))))

(defn playing? [world player-id]
  (and (= :running (get-in world [:match :phase]))
       (game/active-player? (get-in world [:players player-id]))))

(defn add-player [world player-id player]
  (assoc-in world [:players player-id]
            (assoc player :lifecycle :waiting :stocks 0
                   :respawn-seconds-left 0 :protection-seconds-left 0)))

(defn remove-player [world player-id]
  (update world :players dissoc player-id))

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

(defn step [world inputs-by-player dt]
  (if (= :running (get-in world [:match :phase]))
    (step-running world inputs-by-player dt)
    world))

(ns clojure-arena.fixtures
  (:require [clojure-arena.game :as game]))

(def player-path [:players :player-1])

(defn player-of [world]
  (get-in world player-path))

(def single-platform-world
  (-> game/initial-world
      (update :platforms (fn [platforms] (filterv #(= :main (:id %)) platforms)))
      (update :players select-keys [:player-1])))

(defn step [world inputs dt]
  (game/step world {:player-1 inputs} dt))

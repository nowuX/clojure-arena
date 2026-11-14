(ns clojure-arena.fixtures
  (:require [clojure-arena.game :as game]))

(def single-platform-world
  (update game/initial-world :platforms
          (fn [platforms] (filterv #(= :main (:id %)) platforms))))

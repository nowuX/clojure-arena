(ns clojure-arena.client
  (:require [clojure-arena.game :as game]))

(defonce world (atom game/initial-world))

(defn draw-background [canvas]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) "#101010")
    (.fillRect context 0 0 (.-width canvas) (.-height canvas))))

(defn draw-platform [canvas {:keys [x y width height color]}]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) color)
    (.fillRect context x y width height)))

(defn draw-player [canvas {:keys [x y radius color]}]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) color)
    (.beginPath context)
    (.arc context x y radius 0 (* 2 js/Math.PI))
    (.fill context)))

(defn draw-world [canvas {:keys [platform player]}]
  (draw-background canvas)
  (draw-platform canvas platform)
  (draw-player canvas player))

(defn init []
  (set! (.-textContent (.getElementById js/document "status")) "Arena lista.")
  (draw-world (.getElementById js/document "arena") @world))

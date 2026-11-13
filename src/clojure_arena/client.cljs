(ns clojure-arena.client)

(defn draw-background [canvas]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) "#101010")
    (.fillRect context 0 0 (.-width canvas) (.-height canvas))))

(defn draw-platform [canvas]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) "#9c9c9c")
    (.fillRect context 170 280 460 16)))

(defn init []
  (set! (.-textContent (.getElementById js/document "status"))
        "Arena lista.")
  (let [canvas (.getElementById js/document "arena")]
    (draw-background canvas)
    (draw-platform canvas)))

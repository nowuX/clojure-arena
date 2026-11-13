(ns clojure-arena.client)

(defn draw-background [canvas]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) "#101010")
    (.fillRect context 0 0 (.-width canvas) (.-height canvas))))

(defn init []
  (set! (.-textContent (.getElementById js/document "status"))
        "Arena lista.")
  (draw-background (.getElementById js/document "arena")))

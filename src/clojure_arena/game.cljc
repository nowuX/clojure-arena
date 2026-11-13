(ns clojure-arena.game)

(def horizontal-speed 220)

(def initial-world
  {:platform {:x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}
   :player {:x 400 :y 264 :vx 0 :radius 16 :color "#22d3ee"}})

(defn step [world {:keys [horizontal] :or {horizontal 0}} dt]
  (let [vx (* horizontal horizontal-speed)]
    (update world :player
            (fn [player]
              (-> player
                  (assoc :vx vx)
                  (update :x + (* vx dt)))))))

(ns clojure-arena.game)

(def horizontal-speed 220)
(def gravity 980)

(def initial-world
  {:platform {:x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}
   :player {:x 400 :y 160 :vx 0 :vy 0 :radius 16 :color "#22d3ee"}})

(defn step [world {:keys [horizontal] :or {horizontal 0}} dt]
  (let [vx (* horizontal horizontal-speed)]
    (update world :player
            (fn [player]
              (let [vy (+ (:vy player) (* gravity dt))]
                (-> player
                    (assoc :vx vx :vy vy)
                    (update :x + (* vx dt))
                    (update :y + (* vy dt))))))))

(ns clojure-arena.game)

(def horizontal-speed 220)
(def gravity 980)

(def initial-world
  {:platform {:x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}
   :player {:x 400 :y 160 :vx 0 :vy 0 :radius 16 :color "#22d3ee"}})

(defn move-player [player dt]
  (-> player
      (update :x + (* (:vx player) dt))
      (update :y + (* (:vy player) dt))))

(defn supported-travel-time [{:keys [x vx]} platform dt]
  (cond
    (pos? vx) (min dt (/ (- (+ (:x platform) (:width platform)) x) vx))
    (neg? vx) (min dt (/ (- (:x platform) x) vx))
    :else dt))

(defn- move-on-top [player platform dt]
  (let [travel-dt (supported-travel-time player platform dt)
        supported (move-player player travel-dt)
        fall-dt (- dt travel-dt)]
    (if (pos? fall-dt)
      (move-player (assoc supported :vy (* gravity fall-dt)) fall-dt)
      supported)))

(defn land-on-top [player platform dt]
  (let [feet (+ (:y player) (:radius player))
        top (:y platform)
        dy (* (:vy player) dt)
        time (when (and (pos? dy) (<= feet top) (>= (+ feet dy) top))
               (/ (- top feet) dy))
        contact-x (when time (+ (:x player) (* (:vx player) dt time)))]
    (if (and time (<= (:x platform) contact-x (+ (:x platform) (:width platform))))
      (move-on-top (assoc player :x contact-x :y (- top (:radius player)) :vy 0)
                   platform (* dt (- 1 time)))
      (move-player player dt))))

(defn step [world {:keys [horizontal] :or {horizontal 0}} dt]
  (update world :player
          (fn [player]
            (-> player
                (assoc :vx (* horizontal horizontal-speed))
                (update :vy + (* gravity dt))
                (land-on-top (:platform world) dt)))))

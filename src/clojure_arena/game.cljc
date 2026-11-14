(ns clojure-arena.game)

(def horizontal-speed 220)
(def horizontal-acceleration 880)
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

(def contact-epsilon 1e-9)
(def max-contacts-per-tick 4)

(defn- top-supported? [{:keys [x y radius vy]} platform]
  (and (zero? vy)
       (<= (:x platform) x (+ (:x platform) (:width platform)))
       (<= (abs (- (+ y radius) (:y platform))) contact-epsilon)))

(defn sqrt [value]
  #?(:clj (Math/sqrt value)
     :cljs (js/Math.sqrt value)))

(defn overlap-contact [{:keys [x y radius]} platform]
  (let [left (:x platform)
        right (+ left (:width platform))
        top (:y platform)
        bottom (+ top (:height platform))
        nearest-x (max left (min x right))
        nearest-y (max top (min y bottom))
        dx (- x nearest-x)
        dy (- y nearest-y)
        distance (sqrt (+ (* dx dx) (* dy dy)))]
    (when (< distance (- radius contact-epsilon))
      (if (pos? distance)
        (let [nx (/ dx distance)
              ny (/ dy distance)]
          {:time 0 :x (+ nearest-x (* nx radius))
           :y (+ nearest-y (* ny radius)) :nx nx :ny ny})
        (let [face (apply min-key :depth
                          [{:depth (- x left) :x (- left radius) :y y :nx -1 :ny 0}
                           {:depth (- right x) :x (+ right radius) :y y :nx 1 :ny 0}
                           {:depth (- y top) :x x :y (- top radius) :nx 0 :ny -1}
                           {:depth (- bottom y) :x x :y (+ bottom radius) :nx 0 :ny 1}])]
          (assoc face :time 0))))))

(defn face-contact [player dx dy nx ny surface minimum maximum]
  (let [horizontal-face? (zero? nx)
        origin (if horizontal-face? (:y player) (:x player))
        travel (if horizontal-face? dy dx)]
    (when (< (+ (* dx nx) (* dy ny)) (- contact-epsilon))
      (let [time (/ (- surface origin) travel)]
        (when (<= 0 time 1)
          (let [x (+ (:x player) (* dx time))
                y (+ (:y player) (* dy time))
                along-face (if horizontal-face? x y)]
            (when (<= minimum along-face maximum)
              {:time time :x x :y y :nx nx :ny ny})))))))

(defn corner-contact [player dx dy corner-x corner-y quadrant-x quadrant-y]
  (let [rx (- (:x player) corner-x)
        ry (- (:y player) corner-y)
        radius (:radius player)
        a (+ (* dx dx) (* dy dy))
        b (* 2 (+ (* rx dx) (* ry dy)))
        c (- (+ (* rx rx) (* ry ry)) (* radius radius))
        discriminant (- (* b b) (* 4 a c))]
    (when (and (pos? a) (>= discriminant 0))
      (let [time (/ (- (- b) (sqrt discriminant)) (* 2 a))]
        (when (<= (- contact-epsilon) time 1)
          ;; An existing corner contact may round to a tiny negative time.
          (let [time (max 0 time)
                x (+ (:x player) (* dx time))
                y (+ (:y player) (* dy time))
                nx (/ (- x corner-x) radius)
                ny (/ (- y corner-y) radius)]
            (when (and (>= (* nx quadrant-x) 0)
                       (>= (* ny quadrant-y) 0)
                       (< (+ (* dx nx) (* dy ny)) (- contact-epsilon)))
              {:time time :x x :y y :nx nx :ny ny})))))))

(defn platform-contact [player proposed platform]
  (or (overlap-contact player platform)
      (let [left (:x platform)
            right (+ left (:width platform))
            top (:y platform)
            bottom (+ top (:height platform))
            radius (:radius player)
            dx (- (:x proposed) (:x player))
            dy (- (:y proposed) (:y player))]
        (first
          (sort-by :time
                   (keep identity
                         [(face-contact player dx dy 0 -1 (- top radius) left right)
                          (face-contact player dx dy 0 1 (+ bottom radius) left right)
                          (face-contact player dx dy -1 0 (- left radius) top bottom)
                          (face-contact player dx dy 1 0 (+ right radius) top bottom)
                          (corner-contact player dx dy left top -1 -1)
                          (corner-contact player dx dy right top 1 -1)
                          (corner-contact player dx dy left bottom -1 1)
                          (corner-contact player dx dy right bottom 1 1)]))))))

(defn resolve-contact [player {:keys [x y nx ny]}]
  (let [inward-speed (min 0 (+ (* (:vx player) nx) (* (:vy player) ny)))]
    (assoc player :x x :y y
           :vx (- (:vx player) (* inward-speed nx))
           :vy (- (:vy player) (* inward-speed ny)))))

(defn collide-platform [player platform dt]
  (loop [player player
         remaining-dt dt
         contacts-left max-contacts-per-tick
         departing? false]
    (if (or (zero? contacts-left) (not (pos? remaining-dt)))
      player
      (let [travel-dt (if (top-supported? player platform)
                        (supported-travel-time player platform remaining-dt)
                        remaining-dt)
            proposed (move-player player travel-dt)
            contact (if departing?
                      (when-let [overlap (overlap-contact proposed platform)]
                        (assoc overlap :time 1))
                      (platform-contact player proposed platform))]
        (if contact
          (recur (resolve-contact player contact)
                 (- remaining-dt (* travel-dt (:time contact)))
                 (dec contacts-left)
                 false)
          (if (< travel-dt remaining-dt)
            (let [fall-dt (- remaining-dt travel-dt)]
              (recur (assoc proposed :vy (* gravity fall-dt))
                     fall-dt contacts-left true))
            proposed))))))

(defn accelerate-horizontal [vx horizontal dt]
  (if (zero? horizontal)
    0
    (let [target (* horizontal horizontal-speed)
          change (* horizontal-acceleration dt)]
      (cond
        (< vx target) (min target (+ vx change))
        (> vx target) (max target (- vx change))
        :else vx))))

(defn step [world {:keys [horizontal] :or {horizontal 0}} dt]
  (update world :player
          (fn [player]
            (-> player
                (update :vx accelerate-horizontal horizontal dt)
                (update :vy + (* gravity dt))
                (collide-platform (:platform world) dt)))))

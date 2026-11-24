(ns clojure-arena.game)

(def horizontal-speed 220)
(def horizontal-acceleration 880)
(def horizontal-friction 1100)
(def gravity 980)
(def jump-velocity -420)
(def melee-cooldown 0.25)
(def melee-reach 60)
(def melee-hit-percentage 10)
(def melee-knockback-config
  {:base-impulse 200 :percentage-scale 3})
(def cooldown-epsilon 1e-9)

(def initial-stocks 3)
(def respawn-delay 3)
(def spawn-protection-duration 2)

(def dash-speed 400)
(def dash-ticks 8)
(def dash-cooldown 2.5)

(def antiknockback-duration 5)
(def antiknockback-cooldown 10)

(def recover-factor 0.7)
(def recover-cooldown 12)

(defn make-player [color x y]
  {:x x :y y :spawn-x x :spawn-y y
   :vx 0 :vy 0 :knockback-vx 0 :knockback-vy 0 :radius 16
   :spawn-locked? true :air-jumps-left 1
   :drop-through-platform-id nil :color color :facing :right
   :melee-cooldown-left 0 :melee-strike-id 0 :melee-resolved-strike-id 0
   :melee-target-ids #{}
   :accumulated-percentage 0
   :stocks initial-stocks :lifecycle :active
   :respawn-seconds-left 0 :protection-seconds-left 0
   :dash-ticks-left 0 :dash-cooldown-left 0 :dash-stopped? false
   :antiknockback-seconds-left 0 :antiknockback-cooldown-left 0
   :antiknockback-buffered? false
   :recover-cooldown-left 0 :recover-buffered? false})

(def local-player-fixtures
  {:player-1 (make-player "#22d3ee" 400 160)
   :player-2 (make-player "#e11d48" 260 160)
   :player-3 (make-player "#f59e0b" 540 160)
   :player-4 (make-player "#4ade80" 350 160)})

(def initial-world
  {:blast-bounds {:left -80 :right 880 :top -160 :bottom 560}
   :platforms [{:id :main :type :solid :x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}
               {:id :left :type :semisolid :x 200 :y 210 :width 120 :height 8 :color "#9c9c9c"}
               {:id :right :type :semisolid :x 480 :y 210 :width 120 :height 8 :color "#9c9c9c"}
               {:id :upper :type :semisolid :x 340 :y 130 :width 120 :height 8 :color "#9c9c9c"}]
   :players local-player-fixtures})

(defn outside-arena?
  "True only when the whole circle is strictly beyond a configured bound."
  [{:keys [x y radius]} bounds]
  (boolean
    (when-let [{:keys [left right top bottom]} bounds]
      (or (< (+ x radius) left)
          (> (- x radius) right)
          (< (+ y radius) top)
          (> (- y radius) bottom)))))

(defn active-player? [player]
  (and (map? player) (= :active (:lifecycle player :active))))

(defn protected-player? [player]
  (and (active-player? player) (pos? (:protection-seconds-left player 0))))

(defn dashing?
  "True from the activation tick through the eighth dash tick, including hit evaluation."
  [player]
  (and (active-player? player) (pos? (:dash-ticks-left player 0))))

(defn antiknockback-active?
  "Q suppresses new hit impulses while still allowing percentage gains."
  [player]
  (and (active-player? player) (pos? (:antiknockback-seconds-left player 0))))

(defn immune-player?
  "Spawn protection and an active dash both block percentage and impulse."
  [player]
  (or (protected-player? player) (dashing? player)))

(defn grant-spawn-protection [player]
  (if (active-player? player)
    (assoc player :protection-seconds-left spawn-protection-duration)
    player))

(defn- remaining-seconds [seconds dt]
  (let [remaining (- seconds dt)]
    (if (<= remaining cooldown-epsilon) 0 remaining)))

(defn- step-protection [player dt]
  (if (protected-player? player)
    (update player :protection-seconds-left remaining-seconds dt)
    player))

(defn- step-respawn [player dt]
  (if (and (= :respawning (:lifecycle player)) (pos? (:stocks player)))
    (let [remaining (remaining-seconds (:respawn-seconds-left player) dt)]
      (if (zero? remaining)
        (-> (make-player (:color player) (:spawn-x player) (:spawn-y player))
            (assoc :stocks (:stocks player))
            grant-spawn-protection)
        (assoc player :respawn-seconds-left remaining)))
    player))

(defn resolve-ring-out
  "Consume one active life and clear pending physical/hit effects."
  [player bounds]
  (if (and (active-player? player) (outside-arena? player bounds))
    (let [stocks (max 0 (dec (:stocks player initial-stocks)))]
      (assoc player :stocks stocks
             :lifecycle (if (pos? stocks) :respawning :eliminated)
             :respawn-seconds-left (if (pos? stocks) respawn-delay 0)
             :protection-seconds-left 0
             :dash-ticks-left 0 :dash-stopped? false
             :antiknockback-seconds-left 0 :antiknockback-buffered? false
             :recover-buffered? false
             :vx 0 :vy 0 :knockback-vx 0 :knockback-vy 0
             :spawn-locked? true :air-jumps-left 1
             :drop-through-platform-id nil :melee-cooldown-left 0
             :melee-target-ids #{}
             :melee-resolved-strike-id (:melee-strike-id player 0)))
    player))

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

(defn drop-through-platform? [player platform]
  (and (= :semisolid (:type platform))
       (= (:drop-through-platform-id player) (:id platform))))

(defn clear-drop-through [player platforms]
  (if-let [id (:drop-through-platform-id player)]
    (let [platform (first (filter #(= id (:id %)) platforms))]
      (if (or (not= :semisolid (:type platform))
              (< (+ (:y player) (:radius player))
                 (- (:y platform) contact-epsilon))
              (>= (- (:y player) (:radius player))
                  (+ (:y platform) (:height platform))))
        (assoc player :drop-through-platform-id nil)
        player))
    player))

(defn grounded? [{:keys [x y radius vy]} platform]
  (and (zero? vy)
       (<= (:x platform) x (+ (:x platform) (:width platform)))
       (<= (abs (- (+ y radius) (:y platform))) contact-epsilon)))

(defn supporting-platform [player platforms]
  (first (sort-by (comp str :id)
                  (filter #(and (#{:solid :semisolid} (:type %))
                                (not (drop-through-platform? player %))
                                (grounded? player %))
                          platforms))))

(defn apply-jump [player platforms jump?]
  (cond
    (or (not jump?) (:spawn-locked? player)) player
    (supporting-platform player platforms) (assoc player :vy jump-velocity :knockback-vy 0)
    (pos? (:air-jumps-left player)) (-> player
                                       (assoc :vy jump-velocity :knockback-vy 0)
                                       (update :air-jumps-left dec))
    :else player))

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

(defn semisolid-corner-overlap [player platform]
  (when (and (< (:y player) (:y platform))
             (or (< (:x player) (:x platform))
                 (> (:x player) (+ (:x platform) (:width platform)))))
    (overlap-contact player platform)))

(defn semisolid-contact [player proposed platform]
  (let [left (:x platform)
        right (+ left (:width platform))
        top (:y platform)
        dx (- (:x proposed) (:x player))
        dy (- (:y proposed) (:y player))]
    (when (> dy contact-epsilon)
      (or (semisolid-corner-overlap player platform)
          (first
            (sort-by :time
                     (keep identity
                           [(face-contact player dx dy 0 -1
                                          (- top (:radius player)) left right)
                            (corner-contact player dx dy left top -1 -1)
                            (corner-contact player dx dy right top 1 -1)])))))))

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
  (let [inward-speed (min 0 (+ (* (:vx player) nx) (* (:vy player) ny)))
        knockback-vx (:knockback-vx player 0)
        knockback-vy (:knockback-vy player 0)
        ;; Project both shares with the same normal to preserve their sum.
        knockback-normal (if (neg? inward-speed)
                           (+ (* knockback-vx nx) (* knockback-vy ny))
                           0)]
    (assoc player
           :x x :y y
           :vx (- (:vx player) (* inward-speed nx))
           :vy (- (:vy player) (* inward-speed ny))
           :knockback-vx (- knockback-vx (* knockback-normal nx))
           :knockback-vy (- knockback-vy (* knockback-normal ny)))))

(defn earliest-contact [player proposed platforms departing-ids]
  (first
    (sort-by (juxt :time #(str (get-in % [:platform :id])))
             (keep (fn [platform]
                     (when-let [contact
                                (case (:type platform)
                                  :solid
                                  (if (contains? departing-ids (:id platform))
                                    ;; Preserve corner correction at the end of an edge departure.
                                    (when-let [overlap (overlap-contact proposed platform)]
                                      (assoc overlap :time 1))
                                    (platform-contact player proposed platform))
                                  :semisolid
                                  (when-not (drop-through-platform? player platform)
                                    (if (contains? departing-ids (:id platform))
                                      (when-let [overlap (semisolid-corner-overlap proposed platform)]
                                        (assoc overlap :time 1))
                                      (semisolid-contact player proposed platform)))
                                  nil)]
                       (assoc contact :platform platform)))
                   platforms))))

(defn- blocks-dash? [contact dash-direction]
  (and (some? dash-direction)
       (= :solid (get-in contact [:platform :type]))
       (< (* (:nx contact) dash-direction) (- contact-epsilon))))

(defn collide-platforms
  "Sweeps one tick of motion. With a dash direction, a solid contact opposing it
  stops dash travel and consumes the rest of this tick's movement."
  ([player platforms dt]
   (collide-platforms player platforms dt nil))
  ([player platforms dt dash-direction]
   (loop [player player
          remaining-dt dt
          contacts-left max-contacts-per-tick
          departing-ids #{}]
     (if (or (zero? contacts-left) (not (pos? remaining-dt)))
       player
       (let [support (supporting-platform player platforms)
             travel-dt (if support
                         (supported-travel-time player support remaining-dt)
                         remaining-dt)
             proposed (move-player player travel-dt)]
         (if-let [contact (earliest-contact player proposed platforms departing-ids)]
           (let [resolved (resolve-contact player contact)
                 landed (if (grounded? resolved (:platform contact))
                          (assoc resolved :air-jumps-left 1 :spawn-locked? false)
                          resolved)]
             (if (blocks-dash? contact dash-direction)
               (assoc landed :dash-stopped? true)
               (recur landed
                      (- remaining-dt (* travel-dt (:time contact)))
                      (dec contacts-left)
                      #{})))
           (if (< travel-dt remaining-dt)
             (let [fall-dt (- remaining-dt travel-dt)
                   departing-ids (set (map :id
                                          (filter #(and (grounded? proposed %)
                                                        (zero? (supported-travel-time
                                                                 proposed % fall-dt)))
                                                  platforms)))]
               (recur (assoc proposed :vy (* gravity fall-dt))
                      fall-dt
                      contacts-left
                      departing-ids))
             proposed)))))))

(defn apply-horizontal-friction [vx dt]
  (let [change (* horizontal-friction dt)]
    (cond
      (pos? vx) (max 0 (- vx change))
      (neg? vx) (min 0 (+ vx change))
      :else 0)))

(defn accelerate-horizontal [vx horizontal dt]
  (if (zero? horizontal)
    (apply-horizontal-friction vx dt)
    (let [target (* horizontal horizontal-speed)
          change (* horizontal-acceleration dt)]
      (cond
        (< vx target) (min target (+ vx change))
        (> vx target) (max target (- vx change))
        :else vx))))

(defn melee-knockback-impulse
  ([facings post-hit-percentage]
   (melee-knockback-impulse facings post-hit-percentage melee-knockback-config))
  ([facings post-hit-percentage {:keys [base-impulse percentage-scale]}]
   ;; Count directions before scaling so hit order cannot change the sum.
   (let [directions (frequencies facings)
         right (get directions :right 0)
         left (get directions :left 0)
         magnitude (+ base-impulse (* percentage-scale post-hit-percentage))]
     {:dvx (* (- right left) 0.8 magnitude)
      :dvy (* (+ right left) -0.6 magnitude)})))

(defn melee-target-ids [players attacker-id]
  (if-let [attacker (when (active-player? (get players attacker-id))
                     (get players attacker-id))]
    (reduce-kv
      (fn [targets target-id target]
        (let [dx (- (:x target) (:x attacker))
              dy (- (:y target) (:y attacker))]
          (if (and (active-player? target)
                   (not (immune-player? target))
                   (not= target-id attacker-id)
                   (case (:facing attacker)
                     :right (pos? dx)
                     :left (neg? dx)
                     false)
                   (<= (+ (* dx dx) (* dy dy)) (* melee-reach melee-reach)))
            (conj targets target-id)
            targets)))
      #{} players)
    #{}))

(defn step-melee [player attack? dt]
  (if-not (active-player? player)
    player
    (let [remaining (max 0 (- (:melee-cooldown-left player 0) dt))
          remaining (if (<= remaining cooldown-epsilon) 0 remaining)
          player (assoc player :melee-cooldown-left remaining :melee-target-ids #{})]
      (if (and attack? (not (:spawn-locked? player)) (zero? remaining))
        (-> player
            (assoc :melee-cooldown-left melee-cooldown)
            (update :melee-strike-id (fnil inc 0)))
        player))))

(defn- facing-sign [player]
  (if (= :left (:facing player)) -1 1))

(defn- advance-dash
  "Counts down an active dash and its cooldown before this tick's requests."
  [player dt]
  (let [ticks-left (max 0 (dec (:dash-ticks-left player 0)))]
    (assoc player
           :dash-ticks-left ticks-left
           :dash-stopped? (and (pos? ticks-left) (:dash-stopped? player false))
           :dash-cooldown-left (remaining-seconds (:dash-cooldown-left player 0) dt))))

(defn- advance-ability-timers [player dt]
  (assoc player
         :antiknockback-seconds-left (remaining-seconds (:antiknockback-seconds-left player 0) dt)
         :antiknockback-cooldown-left (remaining-seconds (:antiknockback-cooldown-left player 0) dt)
         :recover-cooldown-left (remaining-seconds (:recover-cooldown-left player 0) dt)))

(defn- resolve-buffered-ability
  "Accepts an ability before movement and damage. A request during an active
  dash is buffered and retried on the first tick after the dash; one that is
  still not ready then is discarded."
  [player requested? dash-active? buffered-key ready? activate]
  (let [wanted? (or requested? (get player buffered-key false))]
    (cond
      (not wanted?) player
      dash-active? (assoc player buffered-key true)
      (and (not (:spawn-locked? player)) (ready? player))
      (assoc (activate player) buffered-key false)
      :else (assoc player buffered-key false))))

(defn- antiknockback-ready? [player]
  (and (not (antiknockback-active? player))
       (zero? (:antiknockback-cooldown-left player 0))))

(defn- activate-antiknockback [player]
  (assoc player :antiknockback-seconds-left antiknockback-duration
         :antiknockback-cooldown-left antiknockback-cooldown))

(defn- recover-ready?
  "E needs a ready cooldown and a positive percentage to reduce."
  [player]
  (and (zero? (:recover-cooldown-left player 0))
       (pos? (:accumulated-percentage player 0))))

(defn- activate-recover [player]
  (-> player
      (update :accumulated-percentage #(max 0 (* % recover-factor)))
      (assoc :recover-cooldown-left recover-cooldown)))

(defn- start-dash [player]
  (assoc player :dash-ticks-left dash-ticks :dash-cooldown-left dash-cooldown
         :dash-stopped? false))

(defn- clamp-voluntary-speed [vx]
  (max (- horizontal-speed) (min horizontal-speed vx)))

(defn step-player [player platforms
                   {:keys [horizontal jump? drop? attack? dash? antiknockback? recover?]
                    :or {horizontal 0 jump? false drop? false attack? false dash? false
                         antiknockback? false recover? false}} dt]
  (if-not (active-player? player)
    player
    (let [player (clear-drop-through player platforms)
          dash-ended? (= 1 (:dash-ticks-left player 0))
          player (-> player (advance-dash dt) (advance-ability-timers dt))
          dash-active? (pos? (:dash-ticks-left player))
          player (-> player
                     (resolve-buffered-ability antiknockback? dash-active? :antiknockback-buffered?
                                               antiknockback-ready? activate-antiknockback)
                     (resolve-buffered-ability recover? dash-active? :recover-buffered?
                                               recover-ready? activate-recover))
          locked? (:spawn-locked? player)
          horizontal (if (or locked? dash-active?) 0 horizontal)
          player (cond
                   (neg? horizontal) (assoc player :facing :left)
                   (pos? horizontal) (assoc player :facing :right)
                   :else player)
          support (supporting-platform player platforms)
          ;; One locomotion action per tick: drop, then jump, then dash.
          dropping? (and (not locked?) (not dash-active?) drop?
                         (= :semisolid (:type support)))
          jumping? (and (not locked?) (not dash-active?) (not dropping?) jump?
                        (or (some? support) (pos? (:air-jumps-left player))))
          dash-started? (and dash? (not locked?) (not dash-active?)
                             (not dropping?) (not jumping?)
                             (zero? (:dash-cooldown-left player)))
          dashing-now? (or dash-active? dash-started?)
          player (step-melee player (and attack? (not dashing-now?)) dt)
          player (cond
                   dropping? (assoc player :drop-through-platform-id (:id support))
                   jumping? (apply-jump player platforms true)
                   dash-started? (start-dash player)
                   :else player)
          previous-knockback-vx (:knockback-vx player 0)
          knockback-vx (apply-horizontal-friction previous-knockback-vx dt)
          previous-voluntary-vx (- (:vx player) previous-knockback-vx)
          voluntary-vx (cond
                         (and dashing-now? (:dash-stopped? player)) 0
                         dashing-now? (* (facing-sign player) dash-speed)
                         dash-ended? (accelerate-horizontal
                                       (clamp-voluntary-speed previous-voluntary-vx)
                                       horizontal dt)
                         :else (accelerate-horizontal previous-voluntary-vx horizontal dt))
          moving-player (assoc player :vx (+ voluntary-vx knockback-vx)
                               :knockback-vx knockback-vx)
          dash-direction (when (and dashing-now? (not (:dash-stopped? player)))
                           (facing-sign player))]
      (-> moving-player
          (update :vy + (* gravity dt))
          (collide-platforms platforms dt dash-direction)
          (clear-drop-through platforms)))))

(defn apply-melee-hits [players]
  (let [pending (filter (fn [[_ attacker]]
                          (and (active-player? attacker)
                               (> (:melee-strike-id attacker 0)
                                  (:melee-resolved-strike-id attacker 0))))
                        players)
        hits-by-target
        (reduce (fn [hits [_ attacker]]
                  (reduce (fn [hits target-id]
                            (if (and (contains? players target-id)
                                     (active-player? (get players target-id))
                                     (not (immune-player? (get players target-id))))
                              (update hits target-id (fnil conj []) (:facing attacker))
                              hits))
                          hits (:melee-target-ids attacker)))
                {} pending)
        resolved (reduce (fn [result [attacker-id attacker]]
                           (assoc-in result [attacker-id :melee-resolved-strike-id]
                                     (:melee-strike-id attacker)))
                         players pending)]
    (reduce-kv
      (fn [result target-id facings]
        (let [percentage (+ (get-in players [target-id :accumulated-percentage] 0)
                            (* melee-hit-percentage (count facings)))
              {:keys [dvx dvy]} (melee-knockback-impulse facings percentage)]
          (update result target-id
                  (fn [target]
                    (if (antiknockback-active? target)
                      (assoc target :accumulated-percentage percentage)
                      (-> target
                          (assoc :accumulated-percentage percentage)
                          (update :vx (fnil + 0) dvx)
                          (update :vy (fnil + 0) dvy)
                          (update :knockback-vx (fnil + 0) dvx)
                          (update :knockback-vy (fnil + 0) dvy)))))))
      resolved hits-by-target)))

(defn step [world inputs-by-player dt]
  (let [players (:players world)
        stepped-players
        (reduce-kv (fn [result player-id player]
                     (assoc result player-id
                            (if (active-player? player)
                              (resolve-ring-out
                                (step-player (step-protection player dt) (:platforms world)
                                             (get inputs-by-player player-id) dt)
                                (:blast-bounds world))
                              (step-respawn player dt))))
                   {} players)
        targeted-players
        (reduce-kv
          (fn [result player-id player]
            (if (and (active-player? (get players player-id))
                     (active-player? player)
                     (> (:melee-strike-id player 0)
                        (:melee-strike-id (get players player-id) 0)))
              (assoc-in result [player-id :melee-target-ids]
                        (melee-target-ids stepped-players player-id))
              result))
          stepped-players stepped-players)]
    (assoc world :players (apply-melee-hits targeted-players))))

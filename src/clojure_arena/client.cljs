(ns clojure-arena.client
  (:require [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.transport :as transport]))

(def local-player-id :player-1)
(def tick-ms 25)
(def max-frame-ms 100)
(defonce world (atom game/initial-world))
(defonce ^:private offline-world (atom game/initial-world))
(defonce frame-id (atom nil))
(defonce pressed-keys (atom #{}))
(defonce pending-jump (atom false))
(defonce pending-drop (atom false))
(defonce pending-attack (atom false))
(defonce input-canvas (atom nil))
(def jump-key-code "KeyW")
(def drop-key-code "KeyS")
(def movement-key-codes #{"KeyA" "KeyD"})

(defonce ^:private reset-frame-time? (atom false))

(defn- clear-pending-actions! []
  (reset! pending-jump false)
  (reset! pending-drop false)
  (reset! pending-attack false))

(add-watch transport/connection-state ::clear-local-actions-on-admission
           (fn [_ _ old-state new-state]
             (when (and (not= (:admission old-state) (:admission new-state))
                        (or (= :accepted (:admission old-state))
                            (= :accepted (:admission new-state))))
               (reset! reset-frame-time? true))
             (when (transport/connected?)
               (clear-pending-actions!))
             (when (and (transport/connected?)
                        (not= (:snapshot old-state) (:snapshot new-state))
                        (get-in new-state [:snapshot :world]))
               (reset! world (get-in new-state [:snapshot :world])))
             (when (and (= :accepted (:admission old-state))
                        (not= :accepted (:admission new-state)))
               (reset! world @offline-world))))

(defn horizontal-input []
  (- (if (contains? @pressed-keys "KeyD") 1 0)
     (if (contains? @pressed-keys "KeyA") 1 0)))

(defn read-inputs []
  {:horizontal (horizontal-input) :jump? @pending-jump :drop? @pending-drop :attack? @pending-attack})

(defn consume-inputs! []
  (let [inputs (read-inputs)]
    (reset! pending-jump false)
    (reset! pending-drop false)
    (reset! pending-attack false)
    inputs))

(defn- control-key? [code]
  (or (contains? movement-key-codes code)
      (= code jump-key-code)
      (= code drop-key-code)))

(defn handle-key-down! [event]
  (let [code (.-code event)]
    (when (control-key? code)
      (.preventDefault event)
      (when (and (not (.-repeat event))
                 (not (contains? @pressed-keys code)))
        (cond
          (and (not (transport/connected?)) (= code jump-key-code)) (reset! pending-jump true)
          (and (not (transport/connected?)) (= code drop-key-code)) (reset! pending-drop true))
        (swap! pressed-keys conj code)
        (transport/send-input! {:horizontal (horizontal-input)
                               :jump? (= code jump-key-code)
                               :drop? (= code drop-key-code)
                               :attack? false})))))

(defn handle-key-up! [event]
  (let [code (.-code event)]
    (when (control-key? code)
      (.preventDefault event)
      (when (contains? @pressed-keys code)
        (swap! pressed-keys disj code)
        (transport/send-input! (assoc protocol/neutral-input :horizontal (horizontal-input)))))))

(defn handle-pointer-down! [event]
  (when (zero? (.-button event))
    (.preventDefault event)
    (when-not (transport/connected?) (reset! pending-attack true))
    (transport/send-input! (assoc protocol/neutral-input
                                :horizontal (horizontal-input) :attack? true))))

(defn handle-blur! [_event]
  (reset! pressed-keys #{})
  (reset! pending-jump false)
  (reset! pending-drop false)
  (reset! pending-attack false)
  (transport/send-input! protocol/neutral-input))

(defn draw-background [canvas]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) "#101010")
    (.fillRect context 0 0 (.-width canvas) (.-height canvas))))

(defn draw-platform [canvas {:keys [x y width height color type]}]
  (let [context (.getContext canvas "2d")]
    (if (= type :semisolid)
      (do
        (set! (.-strokeStyle context) color)
        (set! (.-lineWidth context) 2)
        (.strokeRect context x y width height))
      (do
        (set! (.-fillStyle context) color)
        (.fillRect context x y width height)))))

(defn draw-player [canvas {:keys [x y radius color facing]}]
  (let [context (.getContext canvas "2d")]
    (set! (.-fillStyle context) color)
    (.beginPath context)
    (.arc context x y radius 0 (* 2 js/Math.PI))
    (.fill context)
    (set! (.-fillStyle context) "#f2f2f2")
    (.beginPath context)
    (.arc context (+ x (* radius (if (= facing :left) -0.5 0.5)))
          y 3 0 (* 2 js/Math.PI))
    (.fill context)))

(defn draw-world [canvas {:keys [platforms players]}]
  (draw-background canvas)
  (doseq [platform platforms]
    (draw-platform canvas platform))
  (doseq [player (filter game/active-player? (vals players))]
    (draw-player canvas player)))

(defn player-state-label [player]
  (case (:lifecycle player :active)
    :active "Activo"
    :respawning (str "Reapareciendo en " (js/Math.ceil (:respawn-seconds-left player 0)) " s")
    :eliminated "Eliminado"
    "Sin estado"))

(defn render-players! [{:keys [players]}]
  (when-let [panel (.getElementById js/document "players")]
    (.replaceChildren panel)
    (doseq [[player-id player] (sort-by (comp str key) players)]
      (let [row (.createElement js/document "li")
            swatch (.createElement js/document "span")
            label (.createElement js/document "span")
            percentage (.createElement js/document "span")
            stocks (.createElement js/document "span")
            state (.createElement js/document "span")]
        (set! (.-className row) "player-row")
        (set! (.-className swatch) "player-swatch")
        (set! (.-backgroundColor (.-style swatch)) (:color player))
        (.setAttribute swatch "aria-hidden" "true")
        (set! (.-textContent label) (name player-id))
        (set! (.-textContent percentage) (str (:accumulated-percentage player 0) " %"))
        (set! (.-textContent stocks) (str (:stocks player)
                                         (if (= 1 (:stocks player)) " oportunidad" " oportunidades")))
        (set! (.-textContent state) (player-state-label player))
        (.appendChild row swatch)
        (.appendChild row label)
        (.appendChild row percentage)
        (.appendChild row stocks)
        (.appendChild row state)
        (.appendChild panel row)))))

(defn advance-world! [elapsed-ms]
  (if (transport/connected?)
    (do (clear-pending-actions!) 0)
    (do
      (when (= :accepted (:admission @transport/connection-state))
        (reset! world @offline-world))
      (loop [remaining-ms elapsed-ms]
        (if (>= remaining-ms tick-ms)
          (do
            (swap! offline-world game/step
                   {local-player-id (consume-inputs!)} (/ tick-ms 1000))
            (reset! world @offline-world)
            (recur (- remaining-ms tick-ms)))
          remaining-ms)))))

(defn frame [canvas previous-time accumulated-ms timestamp]
  (let [reset-time? (compare-and-set! reset-frame-time? true false)
        elapsed-ms (if (and (not reset-time?) (some? previous-time))
                     (min max-frame-ms (max 0 (- timestamp previous-time)))
                     0)
        remainder (advance-world! (if reset-time? 0 (+ accumulated-ms elapsed-ms)))
        world-snapshot @world]
    (draw-world canvas world-snapshot)
    (render-players! world-snapshot)
    (reset! frame-id
            (js/requestAnimationFrame (partial frame canvas timestamp remainder)))))

(defn stop! []
  (transport/stop!)
  (reset! reset-frame-time? false)
  (when-let [canvas @input-canvas]
    (.removeEventListener canvas "pointerdown" handle-pointer-down!))
  (reset! input-canvas nil)
  (.removeEventListener js/window "keydown" handle-key-down!)
  (.removeEventListener js/window "keyup" handle-key-up!)
  (.removeEventListener js/window "blur" handle-blur!)
  (handle-blur! nil)
  (when-some [id @frame-id]
    (js/cancelAnimationFrame id))
  (reset! frame-id nil))

(defn init []
  (stop!)
  (reset! offline-world @world)
  (.addEventListener js/window "keydown" handle-key-down!)
  (.addEventListener js/window "keyup" handle-key-up!)
  (.addEventListener js/window "blur" handle-blur!)
  (set! (.-textContent (.getElementById js/document "status")) "Arena lista.")
  (let [canvas (.getElementById js/document "arena")
        world-snapshot @world]
    (.addEventListener canvas "pointerdown" handle-pointer-down!)
    (reset! input-canvas canvas)
    (draw-world canvas world-snapshot)
    (render-players! world-snapshot)
    (reset! frame-id (js/requestAnimationFrame (partial frame canvas nil 0))))
  (when-let [location (.-location js/window)]
    (transport/start! (.-href location))))

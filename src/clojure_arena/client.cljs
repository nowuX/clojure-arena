(ns clojure-arena.client
  (:require [clojure-arena.game :as game]))

(def local-player-id :player-1)
(def tick-ms 25)
(def max-frame-ms 100)
(defonce world (atom game/initial-world))
(defonce frame-id (atom nil))
(defonce pressed-keys (atom #{}))
(defonce pending-jump (atom false))
(defonce pending-drop (atom false))
(defonce pending-attack (atom false))
(defonce input-canvas (atom nil))
(def jump-key-code "KeyW")
(def drop-key-code "KeyS")
(def movement-key-codes #{"KeyA" "KeyD"})

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
          (= code jump-key-code) (reset! pending-jump true)
          (= code drop-key-code) (reset! pending-drop true)))
      (swap! pressed-keys conj code))))

(defn handle-key-up! [event]
  (when (control-key? (.-code event))
    (.preventDefault event)
    (swap! pressed-keys disj (.-code event))))

(defn handle-pointer-down! [event]
  (when (zero? (.-button event))
    (.preventDefault event)
    (reset! pending-attack true)))

(defn handle-blur! [_event]
  (reset! pressed-keys #{})
  (reset! pending-jump false)
  (reset! pending-drop false)
  (reset! pending-attack false))

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
  (doseq [player (vals players)]
    (draw-player canvas player)))

(defn render-players! [{:keys [players]}]
  (when-let [panel (.getElementById js/document "players")]
    (.replaceChildren panel)
    (doseq [[player-id player] (sort-by (comp str key) players)]
      (let [row (.createElement js/document "li")
            swatch (.createElement js/document "span")
            label (.createElement js/document "span")
            percentage (.createElement js/document "span")]
        (set! (.-className row) "player-row")
        (set! (.-className swatch) "player-swatch")
        (set! (.-backgroundColor (.-style swatch)) (:color player))
        (.setAttribute swatch "aria-hidden" "true")
        (set! (.-textContent label) (name player-id))
        (set! (.-textContent percentage) (str (:accumulated-percentage player 0) " %"))
        (.appendChild row swatch)
        (.appendChild row label)
        (.appendChild row percentage)
        (.appendChild panel row)))))

(defn advance-world! [elapsed-ms]
  (loop [remaining-ms elapsed-ms]
    (if (>= remaining-ms tick-ms)
      (do
        (swap! world game/step {local-player-id (consume-inputs!)} (/ tick-ms 1000))
        (recur (- remaining-ms tick-ms)))
      remaining-ms)))

(defn frame [canvas previous-time accumulated-ms timestamp]
  (let [elapsed-ms (if (some? previous-time)
                     (min max-frame-ms (max 0 (- timestamp previous-time)))
                     0)
        remainder (advance-world! (+ accumulated-ms elapsed-ms))
        world-snapshot @world]
    (draw-world canvas world-snapshot)
    (render-players! world-snapshot)
    (reset! frame-id
            (js/requestAnimationFrame (partial frame canvas timestamp remainder)))))

(defn stop! []
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
    (reset! frame-id (js/requestAnimationFrame (partial frame canvas nil 0)))))

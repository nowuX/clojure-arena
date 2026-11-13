(ns clojure-arena.client
  (:require [clojure-arena.game :as game]))

(def tick-ms 25)
(def max-frame-ms 100)
(defonce world (atom game/initial-world))
(defonce frame-id (atom nil))
(defonce pressed-keys (atom #{}))
(def movement-key-codes #{"KeyA" "KeyD"})

(defn horizontal-input []
  (- (if (contains? @pressed-keys "KeyD") 1 0)
     (if (contains? @pressed-keys "KeyA") 1 0)))

(defn read-inputs []
  {:horizontal (horizontal-input)})

(defn handle-key-down! [event]
  (when (contains? movement-key-codes (.-code event))
    (.preventDefault event)
    (swap! pressed-keys conj (.-code event))))

(defn handle-key-up! [event]
  (when (contains? movement-key-codes (.-code event))
    (.preventDefault event)
    (swap! pressed-keys disj (.-code event))))

(defn handle-blur! [_event]
  (reset! pressed-keys #{}))

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

(defn advance-world! [elapsed-ms]
  (loop [remaining-ms elapsed-ms]
    (if (>= remaining-ms tick-ms)
      (do
        (swap! world game/step (read-inputs) (/ tick-ms 1000))
        (recur (- remaining-ms tick-ms)))
      remaining-ms)))

(defn frame [canvas previous-time accumulated-ms timestamp]
  (let [elapsed-ms (if (some? previous-time)
                     (min max-frame-ms (max 0 (- timestamp previous-time)))
                     0)
        remainder (advance-world! (+ accumulated-ms elapsed-ms))]
    (draw-world canvas @world)
    (reset! frame-id
            (js/requestAnimationFrame (partial frame canvas timestamp remainder)))))

(defn stop! []
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
  (let [canvas (.getElementById js/document "arena")]
    (draw-world canvas @world)
    (reset! frame-id (js/requestAnimationFrame (partial frame canvas nil 0)))))

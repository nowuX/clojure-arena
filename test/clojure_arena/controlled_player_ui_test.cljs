(ns clojure-arena.controlled-player-ui-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [clojure-arena.client :as client]
            [clojure-arena.hud-test-support :as hud]
            [clojure-arena.transport :as transport]))

(defn window-double []
  #js {:addEventListener (fn [& _] nil)
       :removeEventListener (fn [& _] nil)})

(defn with-dom [dom run]
  (let [names ["document" "window" "cancelAnimationFrame" "requestAnimationFrame"]
        previous (mapv (fn [name] (.getOwnPropertyDescriptor js/Object js/globalThis name)) names)]
    (aset js/globalThis "document" (:document dom))
    (aset js/globalThis "window" (window-double))
    (aset js/globalThis "cancelAnimationFrame" (fn [& _] nil))
    (aset js/globalThis "requestAnimationFrame" (fn [& _] nil))
    (try
      (run)
      (finally
        (doseq [[name descriptor] (map vector names previous)]
          (if descriptor
            (.defineProperty js/Object js/globalThis name descriptor)
            (js/Reflect.deleteProperty js/globalThis name)))))))

(defn reset-client! []
  (reset! transport/connection nil)
  (reset! transport/connection-state {:status :closed})
  (with-redefs [js/cancelAnimationFrame (fn [& _] nil)]
    (client/stop!)))

(defn scenario-world []
  (-> (hud/sample-world)
      (assoc-in [:match :contenders :player-3] {})
      (assoc-in [:players :player-3]
                (assoc (hud/sample-player "#f59e0b" 350)
                       :lifecycle :respawning
                       :respawn-seconds-left 2))
      (assoc-in [:players :player-4] (hud/sample-player "#4ade80" 600))))

(defn row-controlled? [dom player-id]
  (let [row (hud/row-for dom player-id)]
    (boolean (and row
                  (.contains (.-classList row) "player-row-local")
                  (= "true" (.getAttribute row "aria-current"))))))

(defn aria-current [dom player-id]
  (when-let [row (hud/row-for dom player-id)]
    (.getAttribute row "aria-current")))

(defn label-texts [context]
  (hud/canvas-text context))

(defn draw-with-controls! [canvas world drawn-bodies]
  (with-redefs [client/draw-background (fn [_canvas] nil)
                client/draw-platform (fn [_canvas _platform] nil)
                client/draw-player (fn [_canvas player]
                                     (swap! drawn-bodies conj (:x player)))]
    (client/draw-world canvas world)))

(deftest offline-control-label-and-current-row-default-to-player-one
  (let [dom (hud/make-dom)
        counters (:counters dom)
        world (scenario-world)
        ctx (hud/context counters)
        canvas (hud/canvas ctx)
        bodies (atom [])]
    (with-dom dom (fn []
      (reset! transport/connection nil)
      (reset! transport/connection-state {:status :closed})
      (reset-client!)
      (hud/reset-counters! counters)
      (client/render-match! world)
      (set! (.-fillStyle ctx) "#334455")
      (set! (.-font ctx) "10px monospace")
      (set! (.-textAlign ctx) "right")
      (set! (.-textBaseline ctx) "top")
      (let [before [(.-fillStyle ctx) (.-font ctx) (.-textAlign ctx) (.-textBaseline ctx)]]
        (draw-with-controls! canvas world bodies)
        (is (= ["Tú"] (label-texts ctx)))
        (is (= "#f2f2f2" (:fill-style (first (filter #(= :text (:kind %)) @(.-calls ctx)))))
            "the controlled label uses the neutral interface color")
        (is (empty? @(.-stateStack ctx)) "Canvas save/restore calls remain balanced")
        (is (= (:x (get-in world [:players :player-1]))
               (:x (first (filter #(= :text (:kind %)) @(.-calls ctx))))))
        (is (= [200 500] @bodies)
            "only the active contenders are drawn; respawning and observer bodies are skipped")
        (is (= before [(.-fillStyle ctx) (.-font ctx) (.-textAlign ctx) (.-textBaseline ctx)])
            "drawing the label restores fill, font, alignment, and baseline")
        (client/render-match! world)
        (is (row-controlled? dom "player-1"))
        (is (= "player-1" (.-textContent (:id (hud/row-fields (hud/row-for dom "player-1"))))))
        (is (not (row-controlled? dom "player-2")))
        (is (not= "true" (aria-current dom "player-2")))
        (is (= world (scenario-world))
            "label and row rendering leave the supplied snapshot unchanged")
        )
      (reset-client!)))))

(deftest accepted-identity-is-unmarked-before-snapshot-and-controls-after-first-snapshot
  (let [dom (hud/make-dom)
        counters (:counters dom)
        world (scenario-world)
        ctx (hud/context counters)
        canvas (hud/canvas ctx)
        socket #js {:readyState 1 :close (fn [& _] nil)}
        bodies (atom [])]
    (with-dom dom (fn []
      (reset-client!)
      (reset! transport/connection socket)
      (reset! transport/connection-state
              {:status :ready
               :admission :accepted
               :identity {:player-id :player-2 :color :crimson}})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (empty? (label-texts ctx))
          "admission alone does not establish control before the first shared snapshot")
      (is (not (row-controlled? dom "player-1")))
      (is (not (row-controlled? dom "player-2")))
      (is (not= "true" (aria-current dom "player-2")))

      (reset! (.-calls ctx) [])
      (reset! bodies [])
      (reset! transport/connection-state
              {:status :ready
               :admission :accepted
               :identity {:player-id :player-2 :color :crimson}
               :snapshot {:tick 1 :world world}})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (= ["Tú"] (label-texts ctx)))
      (is (row-controlled? dom "player-2"))
      (is (not (row-controlled? dom "player-1")))
      (is (= "true" (aria-current dom "player-2")))

      (reset! (.-calls ctx) [])
      (reset! bodies [])
      (swap! transport/connection-state assoc
             :status :error :error {:kind :network})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (transport/connected?)
          "a transient error keeps an admitted, open session's control identity")
      (is (= ["Tú"] (label-texts ctx)))
      (is (row-controlled? dom "player-2"))
      (is (= world (scenario-world))
          "an error-state HUD render does not alter the snapshot")
      (reset-client!)))))

(deftest inactive-observer-and-stale-session-suppress-floating-labels
  (let [dom (hud/make-dom)
        counters (:counters dom)
        world (scenario-world)
        ctx (hud/context counters)
        canvas (hud/canvas ctx)
        socket #js {:readyState 1 :close (fn [& _] nil)}
        bodies (atom [])]
    (with-dom dom (fn []
      (reset-client!)
      (reset! transport/connection socket)
      (reset! transport/connection-state
              {:status :ready :admission :accepted
               :identity {:player-id :player-3 :color :amber}
               :snapshot {:tick 2 :world world}})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (empty? (label-texts ctx))
          "a respawning controlled identity has no visible body to label")
      (is (row-controlled? dom "player-3")
          "row ownership follows the assigned identity through respawn")

      (reset! (.-calls ctx) [])
      (reset! bodies [])
      (reset! transport/connection-state
              {:status :ready :admission :accepted
               :identity {:player-id :player-4 :color :green}
               :snapshot {:tick 3 :world world}})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (empty? (label-texts ctx))
          "an active observer outside the contender set also has no floating label")
      (is (row-controlled? dom "player-4")
          "the current row remains tied to its assigned ID")

      (reset! (.-calls ctx) [])
      (reset! bodies [])
      (reset! transport/connection #js {:readyState 1 :close (fn [& _] nil)})
      (reset! transport/connection-state
              {:status :ready :admission :accepted
               :identity {:player-id :player-4 :color :green}})
      (client/render-match! world)
      (draw-with-controls! canvas world bodies)
      (is (empty? (label-texts ctx))
          "a reconnect without its own identity and first snapshot cannot reuse stale ownership")
      (is (not (row-controlled? dom "player-1")))
      (is (not (row-controlled? dom "player-3")))
      (is (not (row-controlled? dom "player-4")))
      (reset-client!)))))

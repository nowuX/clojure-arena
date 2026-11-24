(ns clojure-arena.hud-retention-test
  (:require [cljs.test :refer-macros [deftest is testing]]
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

(deftest unchanged-and-movement-only-frames-do-not-write-the-hud
  (let [dom (hud/make-dom)
        counters (:counters dom)
        original (hud/sample-world)
        moved (-> original
                  (assoc-in [:players :player-1 :x] 218)
                  (assoc-in [:players :player-2 :y] 174))]
    (with-dom dom (fn []
      (reset-client!)
      (reset! client/world original)
      (hud/reset-counters! counters)
      (client/render-match! original)
      (let [player-1-row (hud/row-for dom "player-1")
            player-2-row (hud/row-for dom "player-2")
            standing-rows (hud/children (get @(:nodes dom) "match-standings"))
            result-status (.-textContent (get @(:nodes dom) "match-status"))]
        (is (= ["player-1" "player-2"] (mapv hud/row-id (hud/player-rows dom))))
        (is (= "rgb(34, 211, 238)"
               (.-backgroundColor (.-style (:swatch (hud/row-fields player-1-row)))))
            "the DOM double normalizes a hex color like browser CSS does")
        (hud/reset-counters! counters)
        (client/render-match! original)
        (is (= (zipmap hud/counter-keys (repeat 0)) @counters)
            "identical data causes no element creation, value write, or child-list operation")
        (hud/reset-counters! counters)
        (client/render-match! moved)
        (is (= (zipmap hud/counter-keys (repeat 0)) @counters)
            "world-position changes do not affect player rows or standings")
        (is (identical? player-1-row (hud/row-for dom "player-1")))
        (is (identical? player-2-row (hud/row-for dom "player-2")))
        (is (identical? (first standing-rows)
                        (first (hud/children (get @(:nodes dom) "match-standings")))))
        (is (= "Partida 8 en curso · 01:00" result-status))
        (hud/reset-counters! counters)
        (client/render-match! (assoc-in moved [:match :time-seconds-left] 59.1))
        (is (= 0 (:text-writes @counters))
            "clock samples which round to the same displayed second do not rewrite text")
        (is (= 0 (:created @counters)))
        (is (= original @client/world)
            "presentation rendering never mutates the game's world atom"))))))


(deftest changed-values-write-only-the-fields-that-changed
  (let [dom (hud/make-dom)
        counters (:counters dom)
        original (hud/sample-world)
        updated (-> original
                    (assoc-in [:players :player-1 :color] "#4ade80")
                    (assoc-in [:players :player-1 :accumulated-percentage] 29)
                    (assoc-in [:players :player-1 :stocks] 1))]
    (with-dom dom (fn []
      (reset-client!)
      (hud/reset-counters! counters)
      (client/render-match! original)
      (let [row (hud/row-for dom "player-1")]
        (hud/reset-counters! counters)
        (client/render-match! updated)
        (is (identical? row (hud/row-for dom "player-1")))
        (is (= 0 (:created @counters)))
        (is (= 2 (:text-writes @counters))
            "percentage and stock text change while all other text remains untouched")
        (is (= 0 (:class-writes @counters)))
        (is (= 1 (:style-writes @counters))
            "only the changed swatch color is assigned")
        (let [{:keys [swatch percentage stocks]} (hud/row-fields row)]
          (is (= "rgb(74, 222, 128)" (.-backgroundColor (.-style swatch))))
          (is (= "29 %" (.-textContent percentage)))
          (is (= "1 oportunidad" (.-textContent stocks))))
        (hud/reset-counters! counters)
        (client/render-match! updated)
        (is (= (zipmap hud/counter-keys (repeat 0)) @counters)
            "CSS-normalized color reads do not cause recurring writes"))))))

(deftest row-membership-reuses-survivors-and-replaces-only-the-changed-result-container
  (let [dom (hud/make-dom)
        counters (:counters dom)
        first-world (hud/sample-world)
        changed-membership (-> first-world
                               (update :players dissoc :player-2)
                               (assoc-in [:players :player-3]
                                         (hud/sample-player "#f59e0b" 320)))]
    (with-dom dom (fn []
      (reset-client!)
      (hud/reset-counters! counters)
      (client/render-match! first-world)
      (let [surviving-row (hud/row-for dom "player-1")
            removed-row (hud/row-for dom "player-2")
            standings-container (get @(:nodes dom) "match-standings")
            standing-row (first (hud/children standings-container))]
        (hud/reset-counters! counters)
        (client/render-match! changed-membership)
        (is (identical? surviving-row (hud/row-for dom "player-1"))
            "a surviving exact player ID keeps its existing row node")
        (is (nil? (hud/row-for dom "player-2")))
        (is (some? (hud/row-for dom "player-3")))
        (is (= ["player-1" "player-3"] (mapv hud/row-id (hud/player-rows dom))))
        (is (not (identical? removed-row (hud/row-for dom "player-3"))))
        (is (= 9 (:created @counters))
            "one new row and its eight children are created for the new identity")
        (is (<= (:replace-children @counters) 1)
            "only the changed player list may require a child-list replacement")
        (is (identical? standing-row (first (hud/children standings-container))))

        (let [old-player-container (get @(:nodes dom) "players")
              new-player-container (hud/replace-element! dom "players" "ul")
              old-standing-container (get @(:nodes dom) "match-standings")
              new-standing-container (hud/replace-element! dom "match-standings" "ol")]
          (is (not (identical? old-player-container new-player-container)))
          (is (not (identical? old-standing-container new-standing-container)))
          (hud/reset-counters! counters)
          (client/render-match! changed-membership)
          (is (= ["player-1" "player-3"] (mapv hud/row-id (hud/player-rows dom))))
          (is (not (identical? surviving-row (hud/row-for dom "player-1")))
              "a replacement DOM container receives fresh rows")
          (is (not (identical? standing-row
                               (first (hud/children new-standing-container))))
              "a replacement standings container receives a fresh result list")
          (is (pos? (:created @counters)))
          (is (pos? (:replace-children @counters)))))))))

(deftest changed-result-rebuilds-standings-and-stop-clears-row-and-result-caches
  (let [dom (hud/make-dom)
        counters (:counters dom)
        initial (hud/sample-world)
        changed-result (assoc-in initial [:match :result :standings 0 :accumulated-percentage] 40)]
    (with-dom dom (fn []
      (reset-client!)
      (hud/reset-counters! counters)
      (client/render-match! initial)
      (let [player-row (hud/row-for dom "player-1")
            standing-row (first (hud/children (get @(:nodes dom) "match-standings")))]
        (hud/reset-counters! counters)
        (client/render-match! changed-result)
        (is (not (identical? standing-row
                             (first (hud/children (get @(:nodes dom) "match-standings")))))
            "a changed result replaces the standings content")
        (is (identical? player-row (hud/row-for dom "player-1"))
            "result changes leave player rows intact")
        (is (pos? (:replace-children @counters)))
        (let [standing-before-stop (first (hud/children (get @(:nodes dom) "match-standings")))]
          (reset-client!)
          (hud/reset-counters! counters)
          (client/render-match! changed-result)
          (is (not (identical? player-row (hud/row-for dom "player-1")))
              "stop clears the row cache")
          (is (not (identical? standing-before-stop
                               (first (hud/children (get @(:nodes dom) "match-standings")))))
              "stop clears the result-list cache")
          (is (pos? (:created @counters)))
          (is (pos? (:replace-children @counters)))))))))

(deftest canvas-still-draws-on-every-animation-frame
  (let [dom (hud/make-dom)
        counters (:counters dom)
        snapshot (hud/sample-world)
        draws (atom [])
        scheduled (atom [])]
    (with-dom dom (fn []
      (reset-client!)
      (reset! client/world snapshot)
      (with-redefs [client/advance-world! (fn [_elapsed] 0)
                  client/draw-world (fn [_canvas world] (swap! draws conj world))
                  client/render-match! (fn [_world] nil)
                  js/requestAnimationFrame (fn [callback]
                                             (swap! scheduled conj callback)
                                             (count @scheduled))]
      (client/frame nil nil 0 0)
      (client/frame nil 0 0 16)
      (is (= [snapshot snapshot] @draws))
      (is (= 2 (count @scheduled))
          "HUD caching leaves the animation loop drawing every requested frame")
      (is (= snapshot @client/world))
      (reset! client/frame-id nil))))))


(deftest dash-cooldown-rewrites-its-field-only-when-the-displayed-second-changes
  (let [dom (hud/make-dom)
        counters (:counters dom)
        cooling (assoc-in (hud/sample-world) [:players :player-1 :dash-cooldown-left] 2.4)]
    (with-dom dom (fn []
      (reset-client!)
      (client/render-match! cooling)
      (is (= "Dash en 3 s"
             (.-textContent (:dash (hud/row-fields (hud/row-for dom "player-1"))))))
      (hud/reset-counters! counters)
      (client/render-match! (assoc-in cooling [:players :player-1 :dash-cooldown-left] 2.1))
      (is (= 0 (:text-writes @counters)) "a fractional tick within the same second writes nothing")
      (client/render-match! (assoc-in cooling [:players :player-1 :dash-cooldown-left] 1.9))
      (is (= 1 (:text-writes @counters)) "only the Dash field changes when the second changes")))))


(deftest fractional-percentages-render-rounded-and-skip-equal-labels
  (let [dom (hud/make-dom)
        counters (:counters dom)
        healed (assoc-in (hud/sample-world) [:players :player-1 :accumulated-percentage] (* 80 0.7))]
    (with-dom dom (fn []
      (reset-client!)
      (client/render-match! healed)
      (is (= "56 %" (.-textContent (:percentage (hud/row-fields (hud/row-for dom "player-1"))))))
      (hud/reset-counters! counters)
      (client/render-match! (assoc-in healed [:players :player-1 :accumulated-percentage] 56.04))
      (is (= 0 (:text-writes @counters)) "a value that displays identically writes nothing")))))

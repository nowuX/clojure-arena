(ns clojure-arena.client-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure-arena.client :as client]
            [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]))

(def playable-world
  (assoc-in fixtures/single-platform-world [:player :spawn-locked?] false))

(def draw-scene client/draw-world)

(def callbacks (atom {}))
(def next-id (atom 0))
(def listeners (atom {}))
(def target (atom nil))

(defn fire-frame! [timestamp]
  (let [[id callback] (first @callbacks)]
    (assert callback "No RAF scheduled")
    (swap! callbacks dissoc id)
    (callback timestamp)))

(defn key-event! [type code & [repeat?]]
  (let [event (js/Event. type #js {:cancelable true})]
    (aset event "code" code)
    (aset event "repeat" (boolean repeat?))
    (.dispatchEvent @target event)))

(use-fixtures :each
  (fn [run]
    (let [names ["window" "document" "requestAnimationFrame" "cancelAnimationFrame"]
          saved (mapv #(aget js/globalThis %) names)
          event-target (js/EventTarget.)
          add (.-addEventListener event-target)
          remove (.-removeEventListener event-target)]
      (reset! callbacks {})
      (reset! listeners {})
      (reset! target event-target)
      (set! (.-addEventListener event-target)
            (fn [type listener]
              (swap! listeners update type (fnil conj #{}) listener)
              (.call add event-target type listener)))
      (set! (.-removeEventListener event-target)
            (fn [type listener]
              (swap! listeners update type disj listener)
              (.call remove event-target type listener)))
      (aset js/globalThis "window" event-target)
      (aset js/globalThis "document" #js {:getElementById (fn [_] #js {})})
      (aset js/globalThis "requestAnimationFrame"
            (fn [callback]
              (let [id (swap! next-id inc)]
                (swap! callbacks assoc id callback)
                id)))
      (aset js/globalThis "cancelAnimationFrame" #(swap! callbacks dissoc %))
      (reset! client/world playable-world)
      (try
        (with-redefs [client/draw-world (fn [& _])]
          (client/stop!)
          (run)
          (client/stop!))
        (finally
          (doseq [[name value] (map vector names saved)]
            (if (undefined? value)
              (js/Reflect.deleteProperty js/globalThis name)
              (aset js/globalThis name value))))))))

(deftest fixed-ticks-carry-subticks-and-bound-pauses
  (let [ticks (atom [])]
    (with-redefs [game/step (fn [world inputs dt]
                              (swap! ticks conj [inputs dt]) world)]
      (client/init)
      (fire-frame! 0)
      (fire-frame! 10)
      (is (empty? @ticks))
      (fire-frame! 30)
      (is (= 1 (count @ticks)))
      (fire-frame! 125)
      (is (= 5 (count @ticks)))
      (fire-frame! 10000)
      (is (= 9 (count @ticks)))
      (is (every? #(= 0.025 (second %)) @ticks))
      (is (= 1 (count @callbacks))))))

(deftest repeated-init-leaves-one-loop-and-resets-time
  (let [ticks (atom 0)]
    (with-redefs [game/step (fn [world _ _] (swap! ticks inc) world)]
      (dotimes [_ 4] (client/init) (is (= 1 (count @callbacks))))
      (fire-frame! 5000)
      (is (zero? @ticks))
      (fire-frame! 5100)
      (is (= 4 @ticks))
      (client/stop!)
      (is (empty? @callbacks)))))

(deftest horizontal-key-lifetimes-and-clean-listeners
  (client/init)
  (key-event! "keydown" "KeyD")
  (key-event! "keydown" "KeyD" true)
  (is (= {:horizontal 1 :jump? false} (client/read-inputs)))
  (key-event! "keydown" "KeyA")
  (is (= 0 (client/horizontal-input)))
  (key-event! "keyup" "KeyD")
  (is (= -1 (client/horizontal-input)))
  (.dispatchEvent @target (js/Event. "blur"))
  (is (= 0 (client/horizontal-input)))
  (key-event! "keydown" "KeyD")
  (client/init)
  (is (= 0 (client/horizontal-input)))
  (is (= {"keydown" 1 "keyup" 1 "blur" 1} (update-vals @listeners count)))
  (client/stop!)
  (is (every? empty? (vals @listeners))))

(deftest equivalent-ticks-across-render-cadences
  (let [simulate (fn [timestamps]
                   (reset! client/world playable-world)
                   (client/init)
                   (key-event! "keydown" "KeyD")
                   (doseq [timestamp timestamps] (fire-frame! timestamp))
                   @client/world)
        fast (simulate (range 0 1001 10))
        slow (simulate (range 0 1001 100))]
    (is (= fast slow))
    (is (< (abs (- 595.25 (get-in fast [:player :x]))) 1e-8))))

(deftest blur-and-both-directions-brake-the-existing-motion
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyD")
  (doseq [timestamp (range 25 251 25)] (fire-frame! timestamp))
  (is (= 220 (get-in @client/world [:player :vx])))
  (key-event! "keydown" "KeyA")
  (fire-frame! 275)
  (is (= 192.5 (get-in @client/world [:player :vx])))
  (key-event! "keyup" "KeyA")
  (.dispatchEvent @target (js/Event. "blur"))
  (fire-frame! 300)
  (is (= 165 (get-in @client/world [:player :vx])))
  (doseq [timestamp (range 325 476 25)] (fire-frame! timestamp))
  (is (zero? (get-in @client/world [:player :vx])))
  (client/stop!)
  (key-event! "keydown" "KeyD")
  (is (zero? (client/horizontal-input)))
  (is (empty? @callbacks)))

(deftest per-tick-directions-agree-across-render-cadences
  (let [script (vec (concat (repeat 12 1) (repeat 16 -1) (repeat 12 0)))
        simulate (fn [timestamps]
                   (let [tick (atom -1)]
                     (client/stop!)
                     (reset! client/world playable-world)
                     (with-redefs [client/read-inputs
                                   (fn [] {:horizontal (get script (swap! tick inc) 0)})]
                       (client/init)
                       (doseq [timestamp timestamps] (fire-frame! timestamp))
                       (is (= 39 @tick))
                       @client/world)))
        fast (simulate (range 0 1001 10))
        slow (simulate (range 0 1001 100))]
    (is (= fast slow))))

(deftest fast-jump-tap-survives-reads-and-subtick-frame
  (let [seen (atom [])]
    (with-redefs [game/step (fn [world inputs _] (swap! seen conj inputs) world)]
      (client/init)
      (fire-frame! 0)
      (key-event! "keydown" "KeyW")
      (key-event! "keyup" "KeyW")
      (is (:jump? (client/read-inputs)))
      (is (:jump? (client/read-inputs)))
      (fire-frame! 10)
      (is (:jump? (client/read-inputs)))
      (fire-frame! 100)
      (is (= [true false false false] (mapv :jump? @seen)))
      (is (false? (:jump? (client/read-inputs)))))))

(deftest held-w-requires-a-fresh-press
  (client/init)
  (key-event! "keydown" "KeyW")
  (is (:jump? (client/consume-inputs!)))
  (key-event! "keydown" "KeyW" true)
  (key-event! "keydown" "KeyW")
  (is (false? (:jump? (client/consume-inputs!))))
  (key-event! "keyup" "KeyW")
  (key-event! "keydown" "KeyW")
  (is (:jump? (client/consume-inputs!))))

(deftest blur-stop-and-restart-discard-pending-actions
  (client/init)
  (doseq [reset-input! [#(.dispatchEvent @target (js/Event. "blur"))
                       client/stop! client/init]]
    (client/init)
    (key-event! "keydown" "KeyW")
    (key-event! "keydown" "KeyD")
    (reset-input!)
    (is (= {:horizontal 0 :jump? false} (client/read-inputs)))))

(deftest held-spawn-input-waits-for-landing-and-discards-old-w
  (reset! client/world fixtures/single-platform-world)
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyD")
  (key-event! "keydown" "KeyW")
  (let [landed-at (loop [tick 1]
                    (assert (< tick 50))
                    (fire-frame! (* 25 tick))
                    (if (get-in @client/world [:player :spawn-locked?])
                      (do (is (= 400 (get-in @client/world [:player :x])))
                          (recur (inc tick)))
                      tick))]
    (is (= 400 (get-in @client/world [:player :x])))
    (is (false? (:jump? (client/read-inputs))))
    (fire-frame! (* 25 (inc landed-at)))
    (is (> (get-in @client/world [:player :x]) 400))
    (is (game/grounded? (:player @client/world) (first (:platforms @client/world))))))

(deftest real-key-jump-sequence-agrees-across-frame-cadences
  (let [consume client/consume-inputs!
        step game/step
        simulate (fn [timestamps]
                   (let [tick (atom 0)
                         seen (atom [])]
                     (client/stop!)
                     (reset! client/world fixtures/single-platform-world)
                     (with-redefs
                       [client/consume-inputs!
                        (fn []
                          (case (swap! tick inc)
                            1 (do (key-event! "keydown" "KeyD")
                                  (key-event! "keydown" "KeyW"))
                            2 (key-event! "keyup" "KeyW")
                            20 (do (key-event! "keyup" "KeyD")
                                   (key-event! "keydown" "KeyA")
                                   (key-event! "keydown" "KeyW"))
                            21 (key-event! "keyup" "KeyW")
                            30 (do (key-event! "keyup" "KeyA")
                                   (key-event! "keydown" "KeyD")
                                   (key-event! "keydown" "KeyW"))
                            31 (key-event! "keyup" "KeyW")
                            40 (key-event! "keyup" "KeyD")
                            60 (key-event! "keydown" "KeyW")
                            61 (key-event! "keyup" "KeyW")
                            nil)
                          (consume))
                        game/step
                        (fn [world inputs dt]
                          (let [next-world (step world inputs dt)]
                            (swap! seen conj {:input inputs :world next-world})
                            next-world))]
                       (client/init)
                       (doseq [timestamp timestamps] (fire-frame! timestamp))
                       (is (= 80 @tick))
                       @seen)))
        fast (simulate (range 0 2001 10))
        slow (simulate (range 0 2001 100))]
    (is (= fast slow))
    (is (= -395.5 (get-in fast [19 :world :player :vy])))
    (is (= -395.5 (get-in fast [29 :world :player :vy])))
    (is (zero? (get-in fast [29 :world :player :air-jumps-left])))
    (is (true? (get-in fast [59 :input :jump?])))
    (is (pos? (get-in fast [59 :world :player :vy])))
    (is (= 1 (get-in (last fast) [:world :player :air-jumps-left])))))

(deftest repeated-lifecycle-clears-stale-jumps-and-allows-a-fresh-launch
  (dotimes [_ 4]
    (client/init)
    (is (= 1 (count @callbacks)))
    (is (= {"keydown" 1 "keyup" 1 "blur" 1} (update-vals @listeners count)))
    (key-event! "keydown" "KeyD")
    (key-event! "keydown" "KeyW")
    (client/stop!)
    (is (empty? @callbacks))
    (is (every? empty? (vals @listeners)))
    (is (= {:horizontal 0 :jump? false} (client/read-inputs))))
  (reset! client/world (update playable-world :player assoc :y 264 :vy 0))
  (client/init)
  (fire-frame! 0)
  (fire-frame! 25)
  (is (zero? (get-in @client/world [:player :vy])))
  (key-event! "keydown" "KeyW")
  (key-event! "keyup" "KeyW")
  (fire-frame! 50)
  (is (= -395.5 (get-in @client/world [:player :vy]))))

(deftest focus-loss-discards-a-jump-before-its-tick
  (reset! client/world (update playable-world :player assoc :y 264 :vy 0))
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyW")
  (key-event! "keyup" "KeyW")
  (.dispatchEvent @target (js/Event. "blur"))
  (fire-frame! 25)
  (is (game/grounded? (:player @client/world) (first (:platforms @client/world))))
  (key-event! "keydown" "KeyW")
  (fire-frame! 50)
  (is (= -395.5 (get-in @client/world [:player :vy]))))

(deftest renderer-uses-each-platform-entry-once
  (let [drawn (atom [])
        snapshot game/initial-world]
    (with-redefs [client/draw-background (fn [_])
                  client/draw-player (fn [_ _])
                  client/draw-platform (fn [_ platform] (swap! drawn conj platform))]
      (draw-scene nil snapshot)
      (is (= (:platforms snapshot) @drawn))
      (is (= game/initial-world snapshot)))))

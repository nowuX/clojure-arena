(ns clojure-arena.client-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure-arena.client :as client]
            [clojure-arena.game :as game]))

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
      (reset! client/world game/initial-world)
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
  (is (= {:horizontal 1} (client/read-inputs)))
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
                   (reset! client/world game/initial-world)
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
                     (reset! client/world game/initial-world)
                     (with-redefs [client/read-inputs
                                   (fn [] {:horizontal (get script (swap! tick inc) 0)})]
                       (client/init)
                       (doseq [timestamp timestamps] (fire-frame! timestamp))
                       (is (= 39 @tick))
                       @client/world)))
        fast (simulate (range 0 1001 10))
        slow (simulate (range 0 1001 100))]
    (is (= fast slow))))

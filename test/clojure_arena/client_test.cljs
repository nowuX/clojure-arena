(ns clojure-arena.client-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure-arena.client :as client]
            [clojure-arena.game :as game]
            [clojure-arena.fixtures :as fixtures]))

(def playable-world
  (assoc-in fixtures/single-platform-world [:players :player-1 :spawn-locked?] false))

(def draw-scene client/draw-world)
(def render-panel client/render-players!)

(def callbacks (atom {}))
(def next-id (atom 0))
(def listeners (atom {}))
(def target (atom nil))
(def canvas-target (atom nil))

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
      (reset! canvas-target (js/EventTarget.))
      (set! (.-addEventListener event-target)
            (fn [type listener]
              (swap! listeners update type (fnil conj #{}) listener)
              (.call add event-target type listener)))
      (set! (.-removeEventListener event-target)
            (fn [type listener]
              (swap! listeners update type disj listener)
              (.call remove event-target type listener)))
      (aset js/globalThis "window" event-target)
      (aset js/globalThis "document" #js {:getElementById (fn [id] (if (= id "arena") @canvas-target #js {}))})
      (aset js/globalThis "requestAnimationFrame"
            (fn [callback]
              (let [id (swap! next-id inc)]
                (swap! callbacks assoc id callback)
                id)))
      (aset js/globalThis "cancelAnimationFrame" #(swap! callbacks dissoc %))
      (reset! client/world playable-world)
      (try
        (with-redefs [client/draw-world (fn [& _])
                      client/render-players! (fn [& _])]
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
  (is (= {:horizontal 1 :jump? false :drop? false :attack? false} (client/read-inputs)))
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
    (is (< (abs (- 595.25 (get-in fast [:players :player-1 :x]))) 1e-8))))

(deftest blur-and-both-directions-brake-the-existing-motion
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyD")
  (doseq [timestamp (range 25 251 25)] (fire-frame! timestamp))
  (is (= 220 (get-in @client/world [:players :player-1 :vx])))
  (key-event! "keydown" "KeyA")
  (fire-frame! 275)
  (is (= 192.5 (get-in @client/world [:players :player-1 :vx])))
  (key-event! "keyup" "KeyA")
  (.dispatchEvent @target (js/Event. "blur"))
  (fire-frame! 300)
  (is (= 165 (get-in @client/world [:players :player-1 :vx])))
  (doseq [timestamp (range 325 476 25)] (fire-frame! timestamp))
  (is (zero? (get-in @client/world [:players :player-1 :vx])))
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
      (is (= [true false false false] (mapv #(get-in % [:player-1 :jump?]) @seen)))
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
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false} (client/read-inputs)))))

(deftest held-spawn-input-waits-for-landing-and-discards-old-w
  (reset! client/world fixtures/single-platform-world)
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyD")
  (key-event! "keydown" "KeyW")
  (let [landed-at (loop [tick 1]
                    (assert (< tick 50))
                    (fire-frame! (* 25 tick))
                    (if (get-in @client/world [:players :player-1 :spawn-locked?])
                      (do (is (= 400 (get-in @client/world [:players :player-1 :x])))
                          (recur (inc tick)))
                      tick))]
    (is (= 400 (get-in @client/world [:players :player-1 :x])))
    (is (false? (:jump? (client/read-inputs))))
    (fire-frame! (* 25 (inc landed-at)))
    (is (> (get-in @client/world [:players :player-1 :x]) 400))
    (is (game/grounded? (fixtures/player-of @client/world) (first (:platforms @client/world))))))

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
    (is (= -395.5 (get-in fast [19 :world :players :player-1 :vy])))
    (is (= -395.5 (get-in fast [29 :world :players :player-1 :vy])))
    (is (zero? (get-in fast [29 :world :players :player-1 :air-jumps-left])))
    (is (true? (get-in fast [59 :input :player-1 :jump?])))
    (is (pos? (get-in fast [59 :world :players :player-1 :vy])))
    (is (= 1 (get-in (last fast) [:world :players :player-1 :air-jumps-left])))))

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
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false} (client/read-inputs))))
  (reset! client/world (update-in playable-world [:players :player-1] assoc :y 264 :vy 0))
  (client/init)
  (fire-frame! 0)
  (fire-frame! 25)
  (is (zero? (get-in @client/world [:players :player-1 :vy])))
  (key-event! "keydown" "KeyW")
  (key-event! "keyup" "KeyW")
  (fire-frame! 50)
  (is (= -395.5 (get-in @client/world [:players :player-1 :vy]))))

(deftest focus-loss-discards-a-jump-before-its-tick
  (reset! client/world (update-in playable-world [:players :player-1] assoc :y 264 :vy 0))
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyW")
  (key-event! "keyup" "KeyW")
  (.dispatchEvent @target (js/Event. "blur"))
  (fire-frame! 25)
  (is (game/grounded? (fixtures/player-of @client/world) (first (:platforms @client/world))))
  (key-event! "keydown" "KeyW")
  (fire-frame! 50)
  (is (= -395.5 (get-in @client/world [:players :player-1 :vy]))))

(deftest renderer-uses-each-platform-entry-once
  (let [drawn (atom [])
        snapshot game/initial-world]
    (with-redefs [client/draw-background (fn [_])
                  client/draw-player (fn [_ _])
                  client/draw-platform (fn [_ platform] (swap! drawn conj platform))]
      (draw-scene nil snapshot)
      (is (= (:platforms snapshot) @drawn))
      (is (= game/initial-world snapshot)))))

(deftest quick-drop-tap-survives-until-one-of-four-ticks
  (let [seen (atom [])]
    (with-redefs [game/step (fn [world inputs _] (swap! seen conj inputs) world)]
      (client/init)
      (fire-frame! 0)
      (key-event! "keydown" "KeyS")
      (key-event! "keyup" "KeyS")
      (is (:drop? (client/read-inputs)))
      (is (:drop? (client/read-inputs)))
      (fire-frame! 10)
      (is (:drop? (client/read-inputs)))
      (fire-frame! 100)
      (is (= [true false false false] (mapv #(get-in % [:player-1 :drop?]) @seen)))
      (is (false? (:drop? (client/read-inputs)))))))

(deftest held-s-and-autorepeat-need-a-fresh-press
  (client/init)
  (key-event! "keydown" "KeyS")
  (is (:drop? (client/consume-inputs!)))
  (key-event! "keydown" "KeyS" true)
  (key-event! "keydown" "KeyS")
  (is (false? (:drop? (client/consume-inputs!))))
  (key-event! "keyup" "KeyS")
  (key-event! "keydown" "KeyS")
  (is (:drop? (client/consume-inputs!))))

(deftest jump-drop-and-held-direction-have-independent-lifetimes
  (client/init)
  (doseq [code ["KeyW" "KeyS" "KeyA" "KeyD"]]
    (key-event! "keydown" code))
  (is (= {:horizontal 0 :jump? true :drop? true :attack? false} (client/consume-inputs!)))
  (is (= {:horizontal 0 :jump? false :drop? false :attack? false} (client/read-inputs)))
  (key-event! "keyup" "KeyA")
  (is (= {:horizontal 1 :jump? false :drop? false :attack? false} (client/consume-inputs!)))
  (is (= 1 (client/horizontal-input))))

(deftest lifecycle-clears-s-w-and-directions-together
  (doseq [clear-input! [#(.dispatchEvent @target (js/Event. "blur"))
                       client/stop! client/init]]
    (client/init)
    (doseq [code ["KeyW" "KeyS" "KeyD"]] (key-event! "keydown" code))
    (clear-input!)
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false} (client/read-inputs)))))

(deftest captured-drop-leaves-the-supporting-semisolid
  (let [platform (first (filter #(= :left (:id %)) (:platforms game/initial-world)))]
    (reset! client/world (update-in game/initial-world [:players :player-1] assoc
                                 :x 260 :y 194 :vy 0 :spawn-locked? false))
    (client/init)
    (fire-frame! 0)
    (key-event! "keydown" "KeyS")
    (fire-frame! 25)
    (is (not (game/grounded? (fixtures/player-of @client/world) platform)))
    (is (= :left (get-in @client/world [:players :player-1 :drop-through-platform-id])))
    (is (> (get-in @client/world [:players :player-1 :y]) 194))
    (is (false? (:drop? (client/read-inputs))))))

(deftest holding-s-does-not-drop-again-after-landing-on-the-next-semisolid
  (let [upper {:id :upper :type :semisolid :x 100 :y 200 :width 100 :height 8}
        lower (assoc upper :id :lower :y 260)]
    (reset! client/world {:platforms [upper lower]
                          :players {:player-1 (assoc (fixtures/player-of game/initial-world)
                                         :x 150 :y 184 :vy 0 :vx 0 :spawn-locked? false)}})
    (client/init)
    (fire-frame! 0)
    (key-event! "keydown" "KeyS")
    (fire-frame! 25)
    (is (= :upper (get-in @client/world [:players :player-1 :drop-through-platform-id])))
    (doseq [timestamp (range 50 1001 25)] (fire-frame! timestamp))
    (is (= :lower (:id (game/supporting-platform (fixtures/player-of @client/world)
                                                (:platforms @client/world)))))
    (key-event! "keydown" "KeyS" true)
    (fire-frame! 1025)
    (is (game/grounded? (fixtures/player-of @client/world) lower))
    (key-event! "keyup" "KeyS")
    (key-event! "keydown" "KeyS")
    (fire-frame! 1050)
    (is (= :lower (get-in @client/world [:players :player-1 :drop-through-platform-id])))
    (is (> (get-in @client/world [:players :player-1 :y]) 244))))

(deftest a-drop-tap-lost-on-blur-does-not-cross-the-platform
  (let [semi (first (filter #(= :left (:id %)) (:platforms game/initial-world)))]
    (reset! client/world (update-in game/initial-world [:players :player-1] assoc
                                 :x 260 :y 194 :vy 0 :spawn-locked? false))
    (client/init)
    (fire-frame! 0)
    (key-event! "keydown" "KeyS")
    (key-event! "keyup" "KeyS")
    (.dispatchEvent @target (js/Event. "blur"))
    (fire-frame! 25)
    (is (game/grounded? (fixtures/player-of @client/world) semi))
    (is (nil? (get-in @client/world [:players :player-1 :drop-through-platform-id])))))

(deftest renderer-draws-one-to-four-independent-bodies-without-changing-state
  (doseq [n (range 1 5)]
    (let [players (into {} (take n (:players game/initial-world)))
          snapshot (assoc game/initial-world :players players)
          drawn (atom [])]
      (with-redefs [client/draw-background (fn [_])
                    client/draw-platform (fn [_ _])
                    client/draw-player (fn [_ player] (swap! drawn conj player))]
        (draw-scene nil snapshot)
        (is (= n (count @drawn)))
        (is (= (set (vals players)) (set @drawn)))
        (is (= players (:players snapshot)))))))

(deftest local-controls-reach-only-player-1-while-every-body-advances
  (reset! client/world (-> game/initial-world
                           (assoc :platforms [])
                           (update :players update-vals #(assoc % :spawn-locked? false))))
  (client/init)
  (fire-frame! 0)
  (doseq [code ["KeyD" "KeyW" "KeyS"]] (key-event! "keydown" code))
  (fire-frame! 25)
  (let [players (:players @client/world)]
    (is (= 22 (get-in players [:player-1 :vx])))
    (is (= -395.5 (get-in players [:player-1 :vy])))
    (is (= 0 (get-in players [:player-1 :air-jumps-left])))
    (doseq [id [:player-2 :player-3 :player-4]]
      (is (zero? (get-in players [id :vx])))
      (is (= 24.5 (get-in players [id :vy])))
      (is (= 1 (get-in players [id :air-jumps-left]))))
  (is (= {:horizontal 1 :jump? false :drop? false :attack? false} (client/read-inputs)))))

(deftest local-controls-do-not-create-a-missing-player-1
  (reset! client/world (-> game/initial-world
                           (assoc :platforms [])
                           (update :players dissoc :player-1)))
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyD")
  (key-event! "keydown" "KeyW")
  (fire-frame! 25)
  (is (= #{:player-2 :player-3 :player-4} (set (keys (:players @client/world)))))
  (doseq [player (vals (:players @client/world))]
    (is (zero? (:vx player)))
    (is (= 24.5 (:vy player))))
  (is (false? (:jump? (client/read-inputs)))))

(deftest key-intention-changes-facing-before-momentum-and-neutral-input-keeps-it
  (reset! client/world (-> playable-world
                           (assoc :platforms [])
                           (update-in [:players :player-1] assoc :vx 100)))
  (client/init)
  (fire-frame! 0)
  (key-event! "keydown" "KeyA")
  (fire-frame! 25)
  (is (= :left (get-in @client/world [:players :player-1 :facing])))
  (is (= 78 (get-in @client/world [:players :player-1 :vx])))
  (key-event! "keydown" "KeyD")
  (fire-frame! 50)
  (is (= :left (get-in @client/world [:players :player-1 :facing])))
  (is (= 50.5 (get-in @client/world [:players :player-1 :vx])))
  (.dispatchEvent @target (js/Event. "blur"))
  (fire-frame! 75)
  (is (= :left (get-in @client/world [:players :player-1 :facing])))
  (is (= 23 (get-in @client/world [:players :player-1 :vx])))
  (key-event! "keydown" "KeyD")
  (fire-frame! 100)
  (is (= :right (get-in @client/world [:players :player-1 :facing]))))

(deftest renderer-places-a-neutral-dot-on-the-remembered-side
  (doseq [[facing dot-x] [[:left 392] [:right 408]]]
    (let [arcs (atom [])
          context #js {:beginPath (fn [])
                       :arc (fn [x y radius _start _end]
                              (this-as ctx
                                (swap! arcs conj [x y radius (.-fillStyle ctx)])))
                       :fill (fn [])}
          canvas #js {:getContext (fn [_] context)}
          player (assoc (game/make-player "#22d3ee" 400 160) :facing facing)]
      (client/draw-player canvas player)
      (is (= [[400 160 16 "#22d3ee"] [dot-x 160 3 "#f2f2f2"]] @arcs))
      (is (= facing (:facing player)))
      (is (= 400 (:x player))))))

(defn pointer-event! [event-target button]
  (let [event (js/Event. "pointerdown" #js {:cancelable true})]
    (aset event "button" button)
    (.dispatchEvent event-target event)))

(deftest short-primary-clicks-coalesce-and-reach-only-one-of-four-ticks
  (let [seen (atom [])]
    (with-redefs [game/step (fn [world inputs _] (swap! seen conj inputs) world)]
      (client/init)
      (fire-frame! 0)
      (pointer-event! @canvas-target 0)
      (pointer-event! @canvas-target 0)
      (is (:attack? (client/read-inputs)))
      (is (:attack? (client/read-inputs)))
      (fire-frame! 10)
      (is (:attack? (client/read-inputs)))
      (fire-frame! 100)
      (is (= [true false false false] (mapv #(get-in % [:player-1 :attack?]) @seen)))
      (fire-frame! 200)
      (is (= 1 (count (filter #(get-in % [:player-1 :attack?]) @seen)))))))

(deftest other-buttons-and-outside-canvas-do-not-request-melee
  (client/init)
  (doseq [button [1 2]] (pointer-event! @canvas-target button))
  (pointer-event! @target 0)
  (pointer-event! (js/EventTarget.) 0)
  (is (false? (:attack? (client/consume-inputs!)))))

(deftest blur-stop-and-restart-discard-pending-melee
  (doseq [clear! [#(.dispatchEvent @target (js/Event. "blur"))
                 client/stop! client/init]]
    (client/init)
    (pointer-event! @canvas-target 0)
    (is (:attack? (client/read-inputs)))
    (clear!)
    (is (false? (:attack? (client/read-inputs))))))

(deftest replacing-the-canvas-removes-the-old-pointer-listener
  (client/init)
  (let [old-canvas @canvas-target]
    (reset! canvas-target (js/EventTarget.))
    (client/init)
    (pointer-event! old-canvas 0)
    (is (false? (:attack? (client/read-inputs))))
    (pointer-event! @canvas-target 0)
    (is (:attack? (client/consume-inputs!)))
    (client/stop!)
    (pointer-event! @canvas-target 0)
    (is (false? (:attack? (client/read-inputs))))))

(deftest attack-jump-drop-and-direction-have-independent-input-lifetimes
  (client/init)
  (doseq [code ["KeyD" "KeyW" "KeyS"]] (key-event! "keydown" code))
  (pointer-event! @canvas-target 0)
  (is (= {:horizontal 1 :jump? true :drop? true :attack? true}
         (client/consume-inputs!)))
  (is (= {:horizontal 1 :jump? false :drop? false :attack? false}
         (client/read-inputs))))

(defn panel-node []
  (let [children (atom [])
        node #js {:style #js {} :children children
                  :setAttribute (fn [_ _])
                  :appendChild #(swap! children conj %)
                  :replaceChildren #(reset! children [])}]
    node))

(deftest player-panel-uses-identities-swatches-and-unbounded-values
  (let [panel (panel-node)
        doc #js {:getElementById (fn [id] (when (= id "players") panel))
                 :createElement (fn [_] (panel-node))}
        snapshot (assoc game/initial-world :players
                        {:player-2 (assoc (get-in game/initial-world [:players :player-2]) :accumulated-percentage 140)
                         :player-1 (assoc (get-in game/initial-world [:players :player-1]) :accumulated-percentage 50)})]
    (with-redefs [js/document doc]
      (render-panel snapshot)
      (let [rows @(.-children panel)
            fields (mapv #(deref (.-children %)) rows)]
        (is (= ["player-1" "player-2"] (mapv #(.-textContent (nth % 1)) fields)))
        (is (= ["50 %" "140 %"] (mapv #(.-textContent (nth % 2)) fields)))
        (is (= ["#22d3ee" "#e11d48"]
               (mapv #(.-backgroundColor (.-style (first %))) fields))))
      (render-panel (assoc snapshot :players {}))
      (is (empty? @(.-children panel))))
    (is (= 140 (get-in snapshot [:players :player-2 :accumulated-percentage])))))

(deftest canvas-and-player-panel-read-the-same-world-for-a-frame
  (let [seen (atom [])]
    (with-redefs [client/draw-world (fn [_ snapshot] (swap! seen conj snapshot))
                  client/render-players! (fn [snapshot] (swap! seen conj snapshot))]
      (client/init)
      (fire-frame! 0)
      (fire-frame! 25)
      (doseq [[drawn displayed] (partition 2 @seen)]
        (is (identical? drawn displayed))))))

(ns clojure-arena.network-client-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure-arena.client :as client]
            [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]
            [clojure-arena.transport :as transport]))

(def ^:private initial-raf-state
  {:next-id 0
   :callbacks {}
   :pending []
   :cancelled []})

(def ^:private raf-state (atom initial-raf-state))
(def ^:private listener-state (atom {}))
(def ^:private arena-canvas (atom nil))
(def ^:private percentage-list (atom nil))

(defn- fixture-element [tag-name]
  (let [element #js {:tagName tag-name
                     :className ""
                     :textContent ""
                     :style #js {:backgroundColor ""}
                     :setAttribute (fn [& _])
                     :children #js []}]
    (set! (.-appendChild element)
          (fn [child]
            (.push (.-children element) child)
            child))
    (set! (.-replaceChildren element)
          (fn [& _children]
            (set! (.-children element) #js [])
            nil))
    element))

(defn- tracked-event-target []
  (let [target (js/EventTarget.)
        add-event-listener (.-addEventListener target)
        remove-event-listener (.-removeEventListener target)]
    (set! (.-addEventListener target)
          (fn [event-type listener]
            (swap! listener-state update event-type (fnil conj #{}) listener)
            (.call add-event-listener target event-type listener)))
    (set! (.-removeEventListener target)
          (fn [event-type listener]
            (swap! listener-state update event-type disj listener)
            (.call remove-event-listener target event-type listener)))
    target))

(defn- active-listener-counts []
  (into {}
        (keep (fn [[event-type listeners]]
                (when (seq listeners)
                  [event-type (count listeners)]))
              @listener-state)))

(defn- recording-canvas []
  (let [calls (atom [])
        context #js {:fillStyle ""
                     :strokeStyle ""}
        canvas #js {:width 800
                    :height 480}]
    (set! (.-getContext canvas) (fn [_kind] context))
    (set! (.-fillRect context)
          (fn [x y width height]
            (swap! calls conj {:op :fill-rect
                               :fill-style (.-fillStyle context)
                               :x x :y y :width width :height height})))
    (set! (.-strokeRect context)
          (fn [x y width height]
            (swap! calls conj {:op :stroke-rect
                               :stroke-style (.-strokeStyle context)
                               :x x :y y :width width :height height})))
    (set! (.-beginPath context)
          (fn []
            (swap! calls conj {:op :begin-path})))
    (set! (.-arc context)
          (fn [x y radius start end]
            (swap! calls conj {:op :arc
                               :fill-style (.-fillStyle context)
                               :x x :y y :radius radius :start start :end end})))
    (set! (.-fill context)
          (fn []
            (swap! calls conj {:op :fill
                               :fill-style (.-fillStyle context)})))
    {:canvas canvas :calls calls}))

(defn- fixture-canvas []
  (let [canvas (tracked-event-target)
        context #js {:fillStyle ""
                     :strokeStyle ""
                     :fillRect (fn [_x _y _width _height])
                     :strokeRect (fn [_x _y _width _height])
                     :beginPath (fn [])
                     :arc (fn [_x _y _radius _start _end])
                     :fill (fn [])}]
    (set! (.-width canvas) 800)
    (set! (.-height canvas) 480)
    (set! (.-getContext canvas) (fn [_kind] context))
    canvas))

(defn- save-global [property]
  (.getOwnPropertyDescriptor js/Object js/globalThis property))

(defn- restore-global! [property descriptor]
  (if descriptor
    (.defineProperty js/Object js/globalThis property descriptor)
    (js-delete js/globalThis property)))

(defn- request-frame! [callback]
  (let [id (:next-id @raf-state)]
    (swap! raf-state
           (fn [state]
             (-> state
                 (update :next-id inc)
                 (assoc-in [:callbacks id] callback)
                 (update :pending conj id))))
    id))

(defn- cancel-frame! [id]
  (swap! raf-state
         (fn [state]
           (-> state
               (update :cancelled conj id)
               (update :pending
                       (fn [pending]
                         (vec (remove #{id} pending))))))))

(defn- deliver-frame! [timestamp]
  (let [id (first (:pending @raf-state))]
    (when (nil? id)
      (throw (js/Error. "No animation frame is pending.")))
    (let [callback (get-in @raf-state [:callbacks id])]
      (swap! raf-state update :pending
             (fn [pending]
               (vec (remove #{id} pending))))
      (callback timestamp))))

(defn- begin-session! [world]
  (client/stop!)
  (reset! raf-state initial-raf-state)
  (reset! client/world world)
  (client/init))

(defn- install-websocket! []
  (let [sockets (atom [])]
    (set! (.-location js/window) #js {:href "http://arena.example:8082/"})
    (set! (.-WebSocket js/globalThis)
          (fn [url]
            (let [socket #js {:url url :readyState 0 :sent #js [] :closeCount 0}]
              (set! (.-send socket)
                    (fn [message]
                      (.push (aget socket "sent") message)))
              (set! (.-close socket)
                    (fn []
                      (aset socket "closeCount" (inc (aget socket "closeCount")))
                      (set! (.-readyState socket) 2)))
              (swap! sockets conj socket)
              socket)))
    sockets))

(defn- sent-frames [socket]
  (mapv protocol/decode (array-seq (aget socket "sent"))))

(defn- sent-inputs [socket]
  (->> (sent-frames socket)
       (filter #(= :input (:type %)))
       (mapv #(dissoc % :type))))

(defn- open-client-socket! [socket]
  (set! (.-readyState socket) 1)
  ((.-onopen socket) #js {}))

(defn- admit-client-socket! [socket]
  (open-client-socket! socket)
  ((.-onmessage socket)
   #js {:data "{:type :identity :player-id :player-1 :color :cyan}"}))

(defn- start-networked-session! [world]
  (let [sockets (install-websocket!)]
    (begin-session! world)
    {:sockets sockets :socket (last @sockets)}))

(defn- playable-world []
  (-> game/initial-world
      (update :players select-keys [client/local-player-id])
      (assoc-in [:players client/local-player-id :spawn-locked?] false)))

(defn- grounded-world []
  (assoc-in (playable-world) [:players :player-1 :y] 264))

(defn- semisolid-world []
  (update-in (playable-world) [:players :player-1] assoc :x 260 :y 194))

(defn- dispatch-key!
  ([event-type code]
   (dispatch-key! event-type code false))
  ([event-type code repeat?]
   (let [event (js/Event. event-type)]
     (.defineProperty js/Object event "code" #js {:value code})
     (.defineProperty js/Object event "repeat" #js {:value repeat?})
     (.dispatchEvent js/window event))))

(defn- dispatch-blur! []
  (.dispatchEvent js/window (js/Event. "blur")))

(defn- dispatch-pointer-down! [target button]
  (let [event (js/Event. "pointerdown")]
    (.defineProperty js/Object event "button" #js {:value button})
    (.dispatchEvent target event)))

(defn- dispatch-pointer-up! [target]
  (.dispatchEvent target (js/Event. "pointerup")))

(defn- client-fixture [test-fn]
  (let [old-document (save-global "document")
        old-window (save-global "window")
        old-websocket (save-global "WebSocket")
        old-request-frame (save-global "requestAnimationFrame")
        old-cancel-frame (save-global "cancelAnimationFrame")
        old-world @client/world
        old-frame-id @client/frame-id
        old-pressed-keys @client/pressed-keys
        old-pending-jump @client/pending-jump
        old-pending-drop @client/pending-drop
        old-pending-attack @client/pending-attack
        old-input-canvas @client/input-canvas
        old-transport-state @transport/connection-state
        old-arena-canvas @arena-canvas
        canvas (fixture-canvas)
        percentages (fixture-element "ul")
        app #js {:textContent ""}
        document #js {:getElementById
                      (fn [id]
                        (case id
                          "arena" @arena-canvas
                          "status" app
                          "players" @percentage-list
                          nil))
                      :createElement fixture-element}]
    (reset! arena-canvas canvas)
    (reset! percentage-list percentages)
    (reset! raf-state initial-raf-state)
    (reset! client/world game/initial-world)
    (reset! client/frame-id nil)
    (reset! client/pressed-keys #{})
    (reset! client/pending-jump false)
    (reset! client/pending-drop false)
    (reset! client/pending-attack false)
    (reset! client/input-canvas nil)
    (reset! listener-state {})
    (set! (.-document js/globalThis) document)
    (set! (.-window js/globalThis) (tracked-event-target))
    (set! (.-requestAnimationFrame js/globalThis) request-frame!)
    (set! (.-cancelAnimationFrame js/globalThis) cancel-frame!)
    (try
      (test-fn)
      (finally
        (client/stop!)
        (reset! client/world old-world)
        (reset! client/frame-id old-frame-id)
        (reset! client/pressed-keys old-pressed-keys)
        (reset! client/pending-jump old-pending-jump)
        (reset! client/pending-drop old-pending-drop)
        (reset! client/pending-attack old-pending-attack)
        (reset! client/input-canvas old-input-canvas)
        (reset! transport/connection-state old-transport-state)
        (reset! arena-canvas old-arena-canvas)
        (reset! percentage-list nil)
        (reset! raf-state initial-raf-state)
        (reset! listener-state {})
        (restore-global! "document" old-document)
        (restore-global! "window" old-window)
        (restore-global! "WebSocket" old-websocket)
        (restore-global! "requestAnimationFrame" old-request-frame)
        (restore-global! "cancelAnimationFrame" old-cancel-frame)))))

(use-fixtures :each client-fixture)

(deftest client-reinitialization-replaces-its-websocket
  (let [sockets (atom [])]
    (set! (.-location js/window) #js {:href "http://arena.example:8082/"})
    (set! (.-WebSocket js/globalThis)
          (fn [url]
            (let [socket #js {:url url :readyState 0 :closeCount 0}]
              (set! (.-close socket)
                    (fn []
                      (aset socket "closeCount" (inc (aget socket "closeCount")))
                      (set! (.-readyState socket) 2)))
              (swap! sockets conj socket)
              socket)))
    (dotimes [_ 3] (client/init))
    (is (= 3 (count @sockets)))
    (is (= [1 1 0] (mapv #(aget % "closeCount") @sockets)))
    (is (identical? (last @sockets) @transport/connection))
    (is (= 1 (count (:pending @raf-state))))
    (is (= {"keydown" 1 "keyup" 1 "blur" 1 "pointerdown" 1}
           (active-listener-counts)))
    (doseq [socket (butlast @sockets)
            property ["onopen" "onmessage" "onerror" "onclose"]]
      (is (nil? (aget socket property))))
    (client/stop!)
    (is (= [1 1 1] (mapv #(aget % "closeCount") @sockets)))
    (is (nil? @transport/connection))
    (is (empty? (:pending @raf-state)))
    (is (empty? (active-listener-counts)))))

(deftest keyboard-input-events-send-directions-and-quick-actions-immediately
  (let [{:keys [socket]} (start-networked-session! game/initial-world)]
    (admit-client-socket! socket)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyA")
    (dispatch-key! "keyup" "KeyD")
    (dispatch-key! "keyup" "KeyA")

    (dispatch-key! "keydown" "KeyW")
    (is (= {:horizontal 0 :jump? true :drop? false :attack? false}
           (last (sent-inputs socket)))
        "a jump reaches the transport before any animation frame runs")
    (dispatch-key! "keyup" "KeyW")
    (dispatch-key! "keydown" "KeyS")
    (dispatch-key! "keyup" "KeyS")

    (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}
            {:horizontal 0 :jump? false :drop? false :attack? false}
            {:horizontal -1 :jump? false :drop? false :attack? false}
            protocol/neutral-input
            {:horizontal 0 :jump? true :drop? false :attack? false}
            protocol/neutral-input
            {:horizontal 0 :jump? false :drop? true :attack? false}
            protocol/neutral-input]
           (sent-inputs socket))
        "A/D states are independent, and a quick W/S tap sends its fresh press")
    (is (= {:horizontal 0 :jump? true :drop? true :attack? false}
           (client/read-inputs))
        "local one-time requests remain available until the existing simulation consumes them")
    (is (= 1 (count (:pending @raf-state)))
        "input delivery does not depend on running the queued animation frame")))

(deftest repeated-held-keydowns-do-not-send-again-but-a-fresh-press-does
  (let [{:keys [socket]} (start-networked-session! game/initial-world)]
    (admit-client-socket! socket)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyD" true)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keyup" "KeyD")
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyW")
    (dispatch-key! "keydown" "KeyW" true)
    (dispatch-key! "keydown" "KeyW")
    (dispatch-key! "keyup" "KeyW")
    (dispatch-key! "keydown" "KeyW")

    (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}
            protocol/neutral-input
            {:horizontal 1 :jump? false :drop? false :attack? false}
            {:horizontal 1 :jump? true :drop? false :attack? false}
            {:horizontal 1 :jump? false :drop? false :attack? false}
            {:horizontal 1 :jump? true :drop? false :attack? false}]
           (sent-inputs socket))
        "keyboard repeat and duplicate downs while held are ignored; release permits a new press")))

(deftest one-time-actions-are-not-replayed-by-unrelated-input-events
  (let [{:keys [socket]} (start-networked-session! game/initial-world)]
    (admit-client-socket! socket)
    (dispatch-key! "keydown" "KeyW")
    (dispatch-key! "keydown" "KeyS")
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyA")

    (is (= [{:horizontal 0 :jump? true :drop? false :attack? false}
            {:horizontal 0 :jump? false :drop? true :attack? false}
            {:horizontal 1 :jump? false :drop? false :attack? false}
            {:horizontal 0 :jump? false :drop? false :attack? false}]
           (sent-inputs socket))
        "fresh W/S presses send one pulse each, while later movement updates carry no pulse")
    (is (= {:horizontal 0 :jump? true :drop? true :attack? false}
           (client/read-inputs))
        "network event snapshots do not consume local requests")))

(deftest only-primary-pointerdown-on-the-canvas-sends-an-attack
  (let [{:keys [socket]} (start-networked-session! game/initial-world)
        outside (tracked-event-target)]
    (admit-client-socket! socket)
    (dispatch-pointer-down! js/window 0)
    (dispatch-pointer-down! outside 0)
    (dispatch-pointer-down! @arena-canvas 1)
    (dispatch-pointer-down! @arena-canvas 2)
    (is (empty? (sent-inputs socket))
        "non-canvas targets and non-primary buttons send nothing")
    (dispatch-pointer-down! @arena-canvas 0)
    (is (= [{:horizontal 0 :jump? false :drop? false :attack? true}]
           (sent-inputs socket))
        "a primary pointerdown on the arena canvas sends immediately")))

(deftest blur-immediately-sends-neutral-and-clears-local-input
  (let [{:keys [socket]} (start-networked-session! game/initial-world)]
    (admit-client-socket! socket)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyW")
    (dispatch-key! "keydown" "KeyS")
    (dispatch-pointer-down! @arena-canvas 0)

    (is (= {:horizontal 1 :jump? true :drop? true :attack? true}
           (client/read-inputs)))
    (dispatch-blur!)
    (is (= protocol/neutral-input (last (sent-inputs socket)))
        "blur sends a release without waiting for the suspended RAF callback")
    (is (= protocol/neutral-input (client/read-inputs)))
    (is (empty? @client/pressed-keys))
    (is (= 1 (count (:pending @raf-state)))
        "the test leaves the animation frame queued while checking blur delivery")))

(deftest buffered-action-survives-blur-until-identity-admits-the-session
  (let [{:keys [socket]} (start-networked-session! game/initial-world)]
    (open-client-socket! socket)
    (dispatch-key! "keydown" "KeyW")
    (dispatch-blur!)
    (is (= protocol/neutral-input (client/read-inputs)))
    (is (empty? (sent-inputs socket))
        "the connecting session does not send player input before identity")
    ((.-onmessage socket)
     #js {:data "{:type :identity :player-id :player-1 :color :cyan}"})
    (is (= [{:horizontal 0 :jump? true :drop? false :attack? false}]
           (sent-inputs socket))
        "the transport keeps its captured one-time action while coalescing blur neutral")))

(deftest init-stop-cycles-do-not-replay-inputs-or-duplicate-listeners
  (let [sockets (install-websocket!)]
    (begin-session! game/initial-world)
    (let [first-socket (first @sockets)]
      (open-client-socket! first-socket)
      (dispatch-key! "keydown" "KeyD")
      (is (empty? (sent-inputs first-socket))
          "the first session has a buffered input that has not been admitted")
      (client/stop!)
      (client/init)
      (let [second-socket (second @sockets)]
        (is (= 2 (count @sockets)))
        (is (= {"keydown" 1 "keyup" 1 "blur" 1 "pointerdown" 1}
               (active-listener-counts)))
        (open-client-socket! second-socket)
        ((.-onmessage second-socket)
         #js {:data "{:type :identity :player-id :player-2 :color :crimson}"})
        (is (empty? (sent-inputs second-socket))
            "a stopped session's last input is not replayed by reinitialization")
        (dispatch-key! "keydown" "KeyD")
        (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}]
               (sent-inputs second-socket)))
        (client/stop!)
        (is (empty? (active-listener-counts)))))))

(deftest unavailable-server-preserves-local-input-and-simulation
  (set! (.-location js/window) #js {:href "http://arena.example:8082/"})
  (set! (.-WebSocket js/globalThis)
        (fn [_] (throw (js/Error. "WebSocket unavailable"))))
  (begin-session! (grounded-world))
  (is (= :error (:status @transport/connection-state)))
  (let [before @client/world]
    (dispatch-key! "keydown" "KeyD")
    (deliver-frame! 0)
    (deliver-frame! 25)
    (is (> (get-in @client/world [:players :player-1 :x])
           (get-in before [:players :player-1 :x])))
    (is (= :error (:status @transport/connection-state)))
    (is (= 1 (count (:pending @raf-state))))))


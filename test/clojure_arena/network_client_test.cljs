(ns clojure-arena.network-client-test
  (:require [clojure-arena.match-test-helpers :as match-helpers]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
(def ^:private connection-status (atom nil))

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

(defn- admit-as! [socket player-id color]
  (open-client-socket! socket)
  ((.-onmessage socket)
   #js {:data (pr-str {:type :identity :player-id player-id :color color})}))

(defn- receive-snapshot! [socket tick world]
  ((.-onmessage socket)
   #js {:data (pr-str {:type :snapshot :tick tick :world (match-helpers/running-world world)})}))

(defn- start-networked-session! [world]
  (let [sockets (install-websocket!)]
    (begin-session! world)
    {:sockets sockets :socket (last @sockets)}))

(defn- playable-world []
  (-> (match-helpers/running-world game/initial-world)
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
        old-connection-status @connection-status
        canvas (fixture-canvas)
        percentages (fixture-element "ul")
        status (fixture-element "p")
        app #js {:textContent ""}
        document #js {:getElementById
                      (fn [id]
                        (case id
                          "arena" @arena-canvas
                          "status" app
                          "connection-status" @connection-status
                          "players" @percentage-list
                          nil))
                      :createElement fixture-element}]
    (reset! arena-canvas canvas)
    (reset! percentage-list percentages)
    (reset! connection-status status)
    (reset! raf-state initial-raf-state)
    (reset! client/world (match-helpers/running-world game/initial-world))
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
        (reset! connection-status old-connection-status)
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
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))]
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
    (is (= protocol/neutral-input
           (client/read-inputs))
        "accepted input intents go to the server without entering the local simulation")
    (is (= 1 (count (:pending @raf-state)))
        "input delivery does not depend on running the queued animation frame")))

(deftest repeated-held-keydowns-do-not-send-again-but-a-fresh-press-does
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))]
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
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))]
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
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false}
           (client/read-inputs))
        "network event snapshots do not queue actions for local simulation")))

(deftest only-primary-pointerdown-on-the-canvas-sends-an-attack
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))
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
        "a primary pointerdown on the arena canvas sends immediately")
    (is (= protocol/neutral-input (client/read-inputs))
        "accepted attacks are not queued for local simulation")))

(deftest blur-immediately-sends-neutral-and-clears-local-input
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))]
    (admit-client-socket! socket)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyW")
    (dispatch-key! "keydown" "KeyS")
    (dispatch-pointer-down! @arena-canvas 0)

    (is (= {:horizontal 1 :jump? false :drop? false :attack? false}
           (client/read-inputs)))
    (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}
            {:horizontal 1 :jump? true :drop? false :attack? false}
            {:horizontal 1 :jump? false :drop? true :attack? false}
            {:horizontal 1 :jump? false :drop? false :attack? true}]
           (sent-inputs socket))
        "jump, drop, and attack intents are sent before any local frame runs")
    (dispatch-blur!)
    (is (= protocol/neutral-input (last (sent-inputs socket)))
        "blur sends a release without waiting for the suspended RAF callback")
    (is (= protocol/neutral-input (client/read-inputs)))
    (is (empty? @client/pressed-keys))
    (is (= 1 (count (:pending @raf-state)))
        "the test leaves the animation frame queued while checking blur delivery")))

(deftest buffered-action-survives-blur-until-identity-admits-the-session
  (let [{:keys [socket]} (start-networked-session! (match-helpers/running-world game/initial-world))]
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

(deftest admission-clears-local-actions-that-were-buffered-before-the-first-frame
  (let [{:keys [socket]} (start-networked-session! (playable-world))
        inputs-seen (atom [])]
    (open-client-socket! socket)
    (dispatch-key! "keydown" "KeyW")
    (dispatch-pointer-down! @arena-canvas 0)
    (is (= {:horizontal 0 :jump? true :drop? false :attack? true}
           (client/read-inputs)))
    (is (empty? (sent-inputs socket))
        "the connecting session keeps its local fallback and waits for admission")

    ((.-onmessage socket)
     #js {:data "{:type :identity :player-id :player-1 :color :cyan}"})
    (is (transport/connected?))
    (is (= [{:horizontal 0 :jump? true :drop? false :attack? true}]
           (sent-inputs socket))
        "admission sends the buffered network intent")
    (is (= {:horizontal 0 :jump? false :drop? false :attack? false}
           (client/read-inputs))
        "admission clears the local copy before any animation frame runs")

    (set! (.-readyState socket) 3)
    ((.-onclose socket) #js {})
    (with-redefs [game/step (fn [world inputs-by-player _dt]
                             (swap! inputs-seen conj
                                    (get inputs-by-player client/local-player-id))
                             world)]
      (is (= 0 (client/advance-world! client/tick-ms)))
      (is (= [protocol/neutral-input] @inputs-seen)
          "a network intent admitted before the first frame is not replayed after close"))))

(deftest accepted-frames-drop-local-time-but-keep-rendering-and-raf
  (let [{:keys [socket]} (start-networked-session! {:steps 0 :players {}})
        steps (atom [])
        rendered (atom [])
        rendered-players (atom [])]
    (admit-client-socket! socket)
    (is (transport/connected?))
    (dispatch-key! "keydown" "KeyW")
    (dispatch-pointer-down! @arena-canvas 0)
    (is (= [{:horizontal 0 :jump? true :drop? false :attack? false}
            {:horizontal 0 :jump? false :drop? false :attack? true}]
           (sent-inputs socket))
        "accepted key and pointer input reaches the server immediately")
    (is (= protocol/neutral-input (client/read-inputs)))

    ;; Model any action queued before the mode boundary and verify direct advances discard it.
    (reset! client/pending-jump true)
    (reset! client/pending-drop true)
    (reset! client/pending-attack true)
    (with-redefs [game/step (fn [world inputs-by-player _dt]
                             (swap! steps conj
                                    (get inputs-by-player client/local-player-id))
                             (update world :steps inc))
                  client/draw-world (fn [_canvas world]
                                      (swap! rendered conj world))
                  client/render-players!
                  (fn [world] (swap! rendered-players conj (:players world)))]
      (is (= 0 (client/advance-world! 100)))
      (is (= [false false false]
             [@client/pending-jump @client/pending-drop @client/pending-attack]))
      (is (= [] @steps)
          "direct accepted advances never call game/step")
      (deliver-frame! 0)
      (deliver-frame! 100)
      (is (= [] @steps)
          "accepted frames discard capped elapsed time instead of building catch-up")
      (is (= [{:steps 0 :players {}} {:steps 0 :players {}}]
             @rendered))
      (is (= [{} {}] @rendered-players))
      (is (= 1 (count (:pending @raf-state)))
          "the animation loop continues while local simulation is disabled")

      (dispatch-key! "keyup" "KeyW")
      (set! (.-readyState socket) 3)
      ((.-onclose socket) #js {})
      (is (not (transport/connected?)))
      (deliver-frame! 125)
      (is (= [] @steps)
          "the first fallback frame discards elapsed time from the connected interval")
      (deliver-frame! 150)
      (is (= [protocol/neutral-input] @steps)
          "local fallback resumes without replaying accepted W or click intents")
      (is (= [{:steps 0 :players {}}
              {:steps 0 :players {}}
              {:steps 0 :players {}}
              {:steps 1 :players {}}]
             @rendered)))))

(deftest network-snapshots-render-as-one-world-and-offline-fallback-keeps-its-own-world
  (let [offline-world (assoc-in (playable-world) [:players :player-1 :x] 123)
        first-network-world (-> (match-helpers/running-world game/initial-world)
                                (assoc-in [:players :player-1 :x] 700)
                                (assoc-in [:players :player-2 :accumulated-percentage] 30))
        latest-network-world (-> (match-helpers/running-world game/initial-world)
                                 (assoc-in [:players :player-1 :x] 760)
                                 (assoc-in [:players :player-2 :accumulated-percentage] 60))
        {:keys [socket]} (start-networked-session! offline-world)
        rendered-worlds (atom [])
        rendered-player-snapshots (atom [])
        stepped-worlds (atom [])]
    (admit-as! socket :player-2 :crimson)
    (dispatch-key! "keydown" "KeyD")
    (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}]
           (sent-inputs socket))
        "the socket identity remains implicit in the exact player-input frame")

    (with-redefs [game/step (fn [world _inputs-by-player _dt]
                             (swap! stepped-worlds conj world)
                             (update world :local-steps (fnil inc 0)))
                  client/draw-world (fn [_canvas world]
                                      (swap! rendered-worlds conj world))
                  client/render-players!
                  (fn [world]
                    (swap! rendered-player-snapshots conj (:players world)))]
      (receive-snapshot! socket 5 first-network-world)
      (is (= (match-helpers/running-world first-network-world) @client/world)
          "a snapshot installs immediately even before the pending RAF runs")
      (deliver-frame! 0)
      (receive-snapshot! socket 6 latest-network-world)
      (is (= (match-helpers/running-world latest-network-world) @client/world)
          "a newer snapshot replaces the visible world while RAF is between frames")
      (deliver-frame! 25)
      (deliver-frame! 50)
      (is (= (mapv match-helpers/running-world [first-network-world latest-network-world latest-network-world])
             @rendered-worlds))
      (is (= (mapv :players @rendered-worlds)
             @rendered-player-snapshots)
          "canvas and percentage panel render the same captured world on each frame")
      (is (empty? @stepped-worlds)
          "network snapshots render without local game/step calls")

      (set! (.-readyState socket) 3)
      ((.-onclose socket) #js {})
      (is (= offline-world @client/world)
          "closing restores the saved local fixture instead of a server world")
      (is (= 0 (client/advance-world! client/tick-ms)))
      (is (= [offline-world] @stepped-worlds)
          "local physics resumes from the local fixture and never from the network snapshot")
      (is (= offline-world (first @stepped-worlds)))

      (transport/start! "http://arena.example:8082/")
      (let [pending-socket @transport/connection]
        (open-client-socket! pending-socket)
        (receive-snapshot! pending-socket 0 latest-network-world)
        (is (nil? (:snapshot @transport/connection-state))
            "a preadmission snapshot on a new connection cannot replace the local world")
        (let [pending-fallback-world (assoc offline-world :local-steps 1)]
          (is (= 0 (client/advance-world! client/tick-ms)))
          (is (= [offline-world pending-fallback-world] @stepped-worlds)
              "a pending connection continues only its saved local world"))))))

(deftest reinitializing-a-connected-client-starts-a-fresh-character-session
  (let [offline-world (assoc-in (grounded-world) [:players :player-1 :x] 123)
        old-player (assoc (game/make-player "#4ade80" 600 240)
                          :spawn-locked? false :accumulated-percentage 80
                          :vx 200 :knockback-vx 100 :melee-cooldown-left 0.3)
        old-world (assoc (match-helpers/running-world game/initial-world) :players {:player-41 old-player})
        fresh-player (game/make-player "#4ade80" 460 160)
        fresh-world (assoc (match-helpers/running-world game/initial-world) :players {:player-42 fresh-player})
        {:keys [sockets socket]} (start-networked-session! offline-world)]
    (admit-as! socket :player-41 :green)
    (receive-snapshot! socket 90 old-world)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keydown" "KeyW")
    (set! (.-send socket) (fn [_] (throw (js/Error. "Interrupted old session"))))
    (dispatch-pointer-down! @arena-canvas 0)
    (is (= :error (:status @transport/connection-state)))
    (is (= (match-helpers/running-world old-world) @client/world))

    (let [old-handlers (mapv #(aget socket %)
                             ["onopen" "onmessage" "onerror" "onclose"])]
      (client/init)
      (let [current (last @sockets)
            connecting-state @transport/connection-state]
        (is (= 2 (count @sockets)))
        (is (not (identical? socket current)))
        (is (= 1 (aget socket "closeCount")))
        (is (= :connecting (:status connecting-state)))
        (is (every? nil? (map connecting-state [:identity :admission :snapshot])))
        (is (= protocol/neutral-input (client/read-inputs)))
        (is (= offline-world @client/world)
            "reinitialization keeps the offline fixture separate from the old network world")

        (doseq [handler old-handlers]
          (handler #js {:data (pr-str {:type :snapshot :tick 99 :world old-world})}))
        (is (= connecting-state @transport/connection-state))
        (is (identical? current @transport/connection))

        (admit-as! current :player-42 :green)
        (is (empty? (sent-inputs current))
            "old held keys, buffered attack and jump do not enter the new session")
        (receive-snapshot! current 91 fresh-world)
        (deliver-frame! 1000)
        (is (= (match-helpers/running-world fresh-world) @client/world))
        (is (= {:player-id :player-42 :color :green}
               (:identity @transport/connection-state)))
        (is (= "Arena compartida conectada. ID: player-42 · green."
               (.-textContent @connection-status)))
        (is (true? (get-in @client/world [:players :player-42 :spawn-locked?])))
        (is (zero? (get-in @client/world [:players :player-42 :accumulated-percentage])))
        (dispatch-key! "keydown" "KeyD")
        (is (= [(assoc protocol/neutral-input :horizontal 1)]
               (sent-inputs current))
            "fresh controls are captured after reinitialization")))))

(deftest connection-timeout-immediately-restores-offline-physics-and-ignores-late-data
  (let [offline-world (assoc-in (grounded-world) [:players :player-1 :x] 123)
        network-world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-1 :x] 700)
        {:keys [socket]} (start-networked-session! offline-world)
        stepped-worlds (atom [])]
    (admit-as! socket :player-1 :cyan)
    (receive-snapshot! socket 10 network-world)
    (dispatch-key! "keydown" "KeyD")
    (dispatch-key! "keyup" "KeyD")
    (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}
            protocol/neutral-input]
           (sent-inputs socket)))

    (with-redefs [game/step (fn [world inputs-by-player _dt]
                             (swap! stepped-worlds conj
                                    {:world world
                                     :input (get inputs-by-player client/local-player-id)})
                             (update world :local-steps (fnil inc 0)))
                  client/draw-world (fn [_canvas _world])
                  client/render-players! (fn [_players])]
      (deliver-frame! 0)
      (deliver-frame! 100)
      (is (empty? @stepped-worlds)
          "an admitted network world does not run local physics")

      ((.-onmessage socket)
       #js {:data "{:type :error :code :connection-timeout}"})
      (is (= :expired (:admission @transport/connection-state)))
      (is (false? (transport/connected?)))
      (is (= offline-world @client/world)
          "timeout restores the saved local world synchronously")
      (is (= "Conexión expirada; el juego continúa en modo local."
             (.-textContent @connection-status)))

      (receive-snapshot! socket 11 network-world)
      (is (= offline-world @client/world)
          "a late server snapshot cannot replace the restored offline world")

      ;; A delayed close must not capture local input for this expired socket.
      (set! (.-readyState socket) 1)
      (dispatch-key! "keydown" "KeyD")
      (dispatch-key! "keyup" "KeyD")
      (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}
              protocol/neutral-input]
             (sent-inputs socket))
          "offline input after timeout is not sent back to the expired session")

      (deliver-frame! 10000)
      (is (empty? @stepped-worlds)
          "elapsed time from the shared session is discarded on timeout")
      (deliver-frame! 10025)
      (is (= [{:world offline-world :input protocol/neutral-input}]
             @stepped-worlds)
          "the first resumed local step uses only the offline world and fresh input")
      (is (= 1 (:local-steps @client/world))))))

(deftest a-connected-session-with-no-frames-cannot-replay-its-time-on-close
  (let [{:keys [socket]} (start-networked-session! {:steps 0 :players {}})]
    (with-redefs [game/step (fn [world _inputs _dt] (update world :steps inc))]
      (deliver-frame! 0)
      (deliver-frame! 24)
      (admit-client-socket! socket)
      (set! (.-readyState socket) 3)
      ((.-onclose socket) #js {})
      (deliver-frame! 10000)
      (is (zero? (:steps @client/world))
          "both the old partial tick and suspended connected time are discarded")
      (deliver-frame! 10025)
      (is (= 1 (:steps @client/world))
          "fresh local elapsed time resumes at the next normal interval"))))

(deftest init-stop-cycles-do-not-replay-inputs-or-duplicate-listeners
  (let [sockets (install-websocket!)]
    (begin-session! (match-helpers/running-world game/initial-world))
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

(deftest full-room-rejection-keeps-local-play-and-animation-running
  (let [{:keys [socket]} (start-networked-session! (grounded-world))
        stepped-inputs (atom [])]
    (open-client-socket! socket)
    (dispatch-key! "keydown" "KeyD")
    (is (empty? (sent-inputs socket))
        "input captured before admission remains buffered only until rejection")

    (with-redefs [game/step (fn [world inputs-by-player _dt]
                             (swap! stepped-inputs conj
                                    (get inputs-by-player client/local-player-id))
                             (update world :local-steps (fnil inc 0)))
                  client/draw-world (fn [_canvas _world])
                  client/render-players! (fn [_players])]
      (deliver-frame! 0)
      ((.-onmessage socket) #js {:data "{:type :room-full}"})
      (is (= 2 (.-readyState socket)))
      (is (= 1 (aget socket "closeCount")))
      (is (false? (transport/connected?)))
      (is (= :full (:admission @transport/connection-state)))

      ((.-onclose socket) #js {})
      (is (nil? @transport/connection))
      (is (= "La sala está llena; el juego continúa en modo local."
             (.-textContent @connection-status)))
      (deliver-frame! 25)
      (is (= [{:horizontal 1 :jump? false :drop? false :attack? false}]
             @stepped-inputs)
          "local physics continues to receive current input while the room is full")
      (is (= 1 (get-in @client/world [:local-steps])))
      (is (= 1 (count (:pending @raf-state)))
          "the animation loop remains scheduled during local fallback")
      (is (empty? (sent-inputs socket))
          "rejected input is never replayed to the closing socket"))))



(deftest own-eliminated-player-keeps-receiving-authoritative-worlds
  (let [{:keys [socket]} (start-networked-session! (playable-world))
        network-world (assoc-in (match-helpers/running-world game/initial-world) [:players :player-2]
                                (assoc (game/make-player "#e11d48" 380 160)
                                       :stocks 0 :lifecycle :eliminated))
        panels (atom [])]
    (admit-as! socket :player-2 :crimson)
    (receive-snapshot! socket 1 network-world)
    (is (transport/connected?))
    (is (= (match-helpers/running-world network-world) @client/world))
    (with-redefs [game/step (fn [& _] (throw (js/Error. "Connected client must not simulate")))
                  client/render-players! (fn [world] (swap! panels conj world))]
      (deliver-frame! 0)
      (is (= [(match-helpers/running-world network-world)] @panels)))
    (is (= 0 (get-in @client/world [:players :player-2 :stocks])))))

(ns clojure-arena.room
  (:require [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]))

(def tick-seconds 0.025)

(def spawn-config
  {:cyan {:fill "#22d3ee" :x 340 :y 160}
   :crimson {:fill "#e11d48" :x 380 :y 160}
   :amber {:fill "#f59e0b" :x 420 :y 160}
   :green {:fill "#4ade80" :x 460 :y 160}})

(defn make-spawn-player [color]
  (let [{:keys [fill x y]} (get spawn-config color)]
    (game/make-player fill x y)))

(def initial-room
  {:tick 0
   :members {}
   :next-player-number 1
   :inputs-by-player {}
   :world (assoc game/initial-world :players {})})

(defn- join-room [room connection-token]
  (let [members (:members room)]
    (if (contains? members connection-token)
      {:room room
       :effects [{:connection-token connection-token
                  :message (let [{:keys [player-id color]}
                                 (get members connection-token)]
                             {:type :identity
                              :player-id player-id
                              :color color})}]}
      (let [assigned-colors (set (map :color (vals members)))
            color (first (remove assigned-colors protocol/player-colors))]
        (if (and color (< (count members) (count protocol/player-colors)))
          (let [player-id (keyword (str "player-" (:next-player-number room)))
                member {:player-id player-id
                        :color color}]
            {:room (-> room
                       (assoc-in [:members connection-token] member)
                       (assoc-in [:world :players player-id]
                                 (make-spawn-player color))
                       (assoc-in [:inputs-by-player player-id] protocol/neutral-input)
                       (update :next-player-number inc))
             :effects [{:connection-token connection-token
                        :message {:type :identity
                                  :player-id player-id
                                  :color color}}]})
          {:room room
           :effects [{:connection-token connection-token
                      :message {:type :room-full}}]})))))

(defn- clear-pending-actions [inputs-by-player]
  (into {}
        (map (fn [[player-id input]]
               [player-id (assoc input
                                 :jump? false
                                 :drop? false
                                 :attack? false)]))
        inputs-by-player))

(defn- tick-room [room]
  (let [world (game/step (:world room)
                         (:inputs-by-player room)
                         tick-seconds)]
    {:room (-> room
               (assoc :world world)
               (update :tick inc)
               (update :inputs-by-player clear-pending-actions))
     :effects []}))

(defn apply-room-message [room internal-message]
  (let [{:keys [type connection-token input]} (when (map? internal-message)
                                              internal-message)
        fields (when (map? internal-message) (set (keys internal-message)))]
    (cond
      (and (= :join type)
           (= #{:type :connection-token} fields)
           (some? connection-token))
      (join-room room connection-token)

      (and (= :input type)
           (= #{:type :connection-token :input} fields)
           (protocol/player-input? input))
      (if-let [player-id (get-in room [:members connection-token :player-id])]
        {:room (update-in room [:inputs-by-player player-id]
                          protocol/merge-pending-input input)
         :effects []}
        {:room room :effects []})

      (and (= :tick type)
           (= #{:type} fields))
      (tick-room room)

      :else {:room room :effects []})))

(defn make-room [deliver-effects!]
  (let [room-agent (agent {:room initial-room
                           :effects []})]
    (add-watch room-agent
               ::deliver-effects
               (fn [_ _ old-state new-state]
                 (when-not (identical? old-state new-state)
                   (deliver-effects! (:effects new-state)))))
    room-agent))

(defn enqueue-room-message! [room-agent message]
  (send room-agent
        (fn [{:keys [room]}]
          (apply-room-message room message))))

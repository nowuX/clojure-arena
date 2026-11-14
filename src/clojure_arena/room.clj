(ns clojure-arena.room
  (:require [clojure-arena.protocol :as protocol]))

(def initial-room
  {:members {}
   :next-player-number 1})

(defn apply-room-message [room internal-message]
  (if (and (map? internal-message)
           (= #{:type :connection-token} (set (keys internal-message)))
           (= :join (:type internal-message))
           (some? (:connection-token internal-message)))
    (let [connection-token (:connection-token internal-message)
          members (:members room)]
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
                         (update :next-player-number inc))
               :effects [{:connection-token connection-token
                          :message {:type :identity
                                    :player-id player-id
                                    :color color}}]})
            {:room room
             :effects [{:connection-token connection-token
                        :message {:type :room-full}}]}))))
    {:room room
     :effects []}))

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

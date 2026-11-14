(ns clojure-arena.match-test-helpers)

(defn- next-historical-id [players contenders]
  (loop [suffix 999999]
    (let [player-id (keyword (str "player-" suffix))]
      (if (or (contains? players player-id)
              (contains? contenders player-id))
        (recur (dec suffix))
        player-id))))

(defn running-world
  "Attach a valid running match to a world for focused test fixtures.

  Every non-waiting player becomes a contender. When fewer than two current
  contenders exist, test-only forfeited historical entries keep the running
  roster within the wire protocol's 2..4 contender bounds. Player physics and
  lifecycle data are left untouched."
  [world]
  (let [players (:players world)
        contenders (into {}
                         (keep (fn [[player-id player]]
                                 (when-not (= :waiting (:lifecycle player))
                                   [player-id {:color (:color player)
                                               :stocks (:stocks player)
                                               :accumulated-percentage (:accumulated-percentage player)
                                               :forfeited? false}])))
                         players)
        historical-count (max 0 (- 2 (count contenders)))
        contenders (loop [result contenders
                          remaining historical-count]
                     (if (zero? remaining)
                       result
                       (let [player-id (next-historical-id players result)]
                         (recur (assoc result player-id
                                       {:color "#000000"
                                        :stocks 0
                                        :accumulated-percentage 0
                                        :forfeited? true})
                                (dec remaining)))))]
    (assoc world :match {:id 1
                         :phase :running
                         :countdown-seconds-left 0
                         :contenders contenders
                         :time-seconds-left 180
                         :intermission-seconds-left 0
                         :result nil})))

(defn running-room
  "Attach running-world's match fixture to a room without changing its physics."
  [room]
  (update room :world running-world))

(defn active-room [room]
  (-> room
      (update-in [:world :players]
                 (fn [players]
                   (into {} (map (fn [[id player]]
                                   [id (if (= :waiting (:lifecycle player))
                                         (assoc player :stocks 3 :lifecycle :active)
                                         player)])) players)))
      running-room))

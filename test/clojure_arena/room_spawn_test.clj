(ns clojure-arena.room-spawn-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [clojure-arena.game :as game]
            [clojure-arena.room :as room]))

(def spawn-cases
  [{:connection-token :connection-a
    :player-id :player-1
    :color :cyan
    :fill "#22d3ee"
    :x 340}
   {:connection-token :connection-b
    :player-id :player-2
    :color :crimson
    :fill "#e11d48"
    :x 380}
   {:connection-token :connection-c
    :player-id :player-3
    :color :amber
    :fill "#f59e0b"
    :x 420}
   {:connection-token :connection-d
    :player-id :player-4
    :color :green
    :fill "#4ade80"
    :x 460}])

(defn- join-room [state connection-token]
  (room/apply-room-message state
                           {:type :join
                            :connection-token connection-token}))

(defn- join-sequence [connection-tokens]
  (loop [state room/initial-room
         tokens (seq connection-tokens)
         results []]
    (if-let [connection-token (first tokens)]
      (let [result (join-room state connection-token)]
        (recur (:room result) (next tokens) (conj results result)))
      results)))

(defn- expected-player [{:keys [x fill]}]
  {:x x
   :y 160
   :vx 0
   :vy 0
   :knockback-vx 0
   :knockback-vy 0
   :facing :right
   :spawn-locked? true
   :air-jumps-left 1
   :drop-through-platform-id nil
   :melee-cooldown-left 0
   :melee-strike-id 0
   :melee-resolved-strike-id 0
   :melee-target-ids #{}
   :accumulated-percentage 0
   :stocks 3 :lifecycle :active
   :radius 16
   :color fill})

(deftest initial-room-keeps-browser-fixtures-out-of-the-room-world-test
  (let [browser-world-before game/initial-world]
    (is (= {:tick 0
            :members {}
            :next-player-number 1
            :inputs-by-player {}
            :world (assoc game/initial-world :players {})}
           room/initial-room))
    (is (= (:platforms game/initial-world)
           (get-in room/initial-room [:world :platforms])))
    (is (= {} (get-in room/initial-room [:world :players])))
    (is (seq (:players game/initial-world))
        "the browser's local fixture world should remain populated")
    (is (= browser-world-before game/initial-world)
        "room initialization should leave browser fixtures unchanged")))

(deftest spawn-config-and-first-four-joins-create-matching-world-players-test
  (let [results (join-sequence (map :connection-token spawn-cases))
        final-room (:room (last results))
        expected-members
        (into {} (map (fn [{:keys [connection-token player-id color]}]
                        [connection-token {:player-id player-id :color color}])
                      spawn-cases))
        expected-players
        (into {} (map (fn [{:keys [player-id] :as spawn}]
                        [player-id (expected-player spawn)])
                      spawn-cases))]
    (doseq [{:keys [color fill x]} spawn-cases]
      (is (= {:fill fill :x x :y 160}
             (room/spawn-config color))))
    (is (= (mapv :color spawn-cases)
           (mapv (comp :color :message first :effects) results)))
    (is (= (mapv :player-id spawn-cases)
           (mapv (comp :player-id :message first :effects) results)))
    (doseq [[index result] (map-indexed vector results)]
      (let [state (:room result)
            expected-count (inc index)
            member-player-ids (set (map :player-id (vals (:members state))))
            world-player-ids (set (keys (get-in state [:world :players])))]
        (is (= expected-count (count (:members state))))
        (is (= expected-count (count (get-in state [:world :players]))))
        (is (= member-player-ids world-player-ids)
            "each pure join transition should commit its member and player together")))
    (is (= expected-members (:members final-room)))
    (is (= expected-players (get-in final-room [:world :players])))
    (is (= #{:blast-bounds :platforms :players} (set (keys (:world final-room)))))
    (is (= [340 380 420 460]
           (mapv :x (map expected-player spawn-cases))))
    (is (apply distinct? (map :x (map expected-player spawn-cases)))
        "spawn centers should be distinct over the central platform")
    (let [platforms (get-in final-room [:world :platforms])
          main (first (filter #(= :main (:id %)) platforms))
          left (first (filter #(= :left (:id %)) platforms))
          right (first (filter #(= :right (:id %)) platforms))
          upper (first (filter #(= :upper (:id %)) platforms))]
      (doseq [player (vals (get-in final-room [:world :players]))]
        (is (<= (:x main) (:x player) (+ (:x main) (:width main)))
            "spawn center should be over the central platform")
        (is (< (+ (:y player) (:radius player)) (:y main))
            "spawn should begin above the central platform")
        (is (or (> (- (:x player) (:radius player))
                   (+ (:x left) (:width left)))
                (< (+ (:x player) (:radius player)) (:x left)))
            "spawn should clear the left semisolid")
        (is (or (> (- (:x player) (:radius player))
                   (+ (:x right) (:width right)))
                (< (+ (:x player) (:radius player)) (:x right)))
            "spawn should clear the right semisolid")
        (is (> (- (:y player) (:radius player))
               (+ (:y upper) (:height upper)))
            "spawn should begin below the upper semisolid underside")))))

(deftest joined-world-is-edn-safe-and-contains-no-connection-data-test
  (let [state (:room (last (join-sequence (map :connection-token spawn-cases))))
        world (:world state)
        values (tree-seq coll? seq world)
        connection-tokens (set (map :connection-token spawn-cases))]
    (is (= world (edn/read-string (pr-str world))))
    (is (not-any? fn? values)
        "the world should contain only serializable data")
    (is (not-any? meta values)
        "the world should not carry metadata")
    (is (empty? (set/intersection connection-tokens (set values)))
        "connection tokens belong to room membership and should not enter the world")
    (is (not (contains? world :members)))))

(deftest duplicate-join-preserves-the-existing-play-state-exactly-test
  (let [joined (:room (join-room room/initial-room :returning-connection))
        player-id (get-in joined [:members :returning-connection :player-id])
        played-player (assoc (get-in joined [:world :players player-id])
                             :x 511
                             :y 222
                             :vx 37
                             :vy -81
                             :knockback-vx -12
                             :knockback-vy 48
                             :air-jumps-left 0
                             :spawn-locked? false
                             :drop-through-platform-id :left
                             :melee-cooldown-left 0.17
                             :melee-strike-id 8
                             :melee-resolved-strike-id 6
                             :melee-target-ids #{:player-2 :player-4}
                             :accumulated-percentage 55)
        played-room (assoc-in joined [:world :players player-id] played-player)
        duplicate (join-room played-room :returning-connection)]
    (is (identical? played-room (:room duplicate)))
    (is (= played-room (:room duplicate)))
    (is (= [{:connection-token :returning-connection
             :message {:type :identity :player-id player-id :color :cyan}}]
           (:effects duplicate)))
    (is (= played-player (get-in (:room duplicate) [:world :players player-id])))))

(deftest fifth-join-keeps-the-exact-world-and-player-counter-test
  (let [full-room (:room (last (join-sequence (map :connection-token spawn-cases))))
        world-before (:world full-room)
        rejected (join-room full-room :connection-e)]
    (is (identical? full-room (:room rejected)))
    (is (identical? world-before (get-in rejected [:room :world])))
    (is (= 5 (get-in rejected [:room :next-player-number])))
    (is (= (set (map :player-id spawn-cases))
           (set (keys (get-in rejected [:room :world :players])))))
    (is (= [{:connection-token :connection-e :message {:type :room-full}}]
           (:effects rejected)))))

(deftest player-state-is-independent-across-changes-and-later-joins-test
  (let [first-room (:room (join-room room/initial-room :first-connection))
        first-id (get-in first-room [:members :first-connection :player-id])
        first-played (assoc (get-in first-room [:world :players first-id])
                            :spawn-locked? false
                            :air-jumps-left 0
                            :melee-cooldown-left 0.12
                            :melee-strike-id 4
                            :melee-resolved-strike-id 3
                            :melee-target-ids #{:opponent}
                            :accumulated-percentage 30)
        first-updated (assoc-in first-room [:world :players first-id] first-played)
        second-room (:room (join-room first-updated :second-connection))
        second-id (get-in second-room [:members :second-connection :player-id])
        second-fresh (get-in second-room [:world :players second-id])
        second-played (assoc second-fresh
                             :spawn-locked? false
                             :air-jumps-left 0
                             :melee-cooldown-left 0.21
                             :melee-strike-id 2
                             :melee-resolved-strike-id 1
                             :melee-target-ids #{:other-opponent}
                             :accumulated-percentage 10)
        second-updated (assoc-in second-room [:world :players second-id] second-played)
        third-room (:room (join-room second-updated :third-connection))
        third-id (get-in third-room [:members :third-connection :player-id])]
    (is (= first-played (get-in second-room [:world :players first-id])))
    (is (= (expected-player (nth spawn-cases 1)) second-fresh))
    (is (= second-played (get-in third-room [:world :players second-id])))
    (is (= first-played (get-in third-room [:world :players first-id])))
    (is (= (expected-player (nth spawn-cases 2))
           (get-in third-room [:world :players third-id]))
        "a later join should receive independent lock, jump, and combat state")
    (is (not (identical? first-played second-played)))))

(deftest actual-spawns-land-and-refill-jumps-one-player-at-a-time-test
  (let [joined-room (:room (last (join-sequence (map :connection-token spawn-cases))))
        first-id :player-1
        second-id :player-2
        world (-> (:world joined-room)
                  (assoc-in [:players first-id :y] 258)
                  (assoc-in [:players first-id :vy] 240)
                  (assoc-in [:players first-id :air-jumps-left] 0)
                  (assoc-in [:players second-id :air-jumps-left] 0))
        first-tick (game/step world {} 0.025)
        first-player (get-in first-tick [:players first-id])
        still-falling-player (get-in first-tick [:players second-id])
        completed (nth (iterate #(game/step % {} 0.025) first-tick) 40)]
    (is (= (:platforms game/initial-world) (:platforms world)))
    (is (== 264 (:y first-player)))
    (is (false? (:spawn-locked? first-player)))
    (is (= 1 (:air-jumps-left first-player))
        "landing should refill the first player's spent air jump")
    (is (:spawn-locked? still-falling-player)
        "another player should stay locked until that player lands")
    (is (= 0 (:air-jumps-left still-falling-player))
        "another player's spent jump should not be refilled by the first landing")
    (doseq [[player-id player] (:players completed)]
      (is (== 264 (:y player)) (str player-id " should land on the central platform"))
      (is (false? (:spawn-locked? player)) (str player-id " should unlock on landing"))
      (is (= 1 (:air-jumps-left player)) (str player-id " should have one air jump")))))

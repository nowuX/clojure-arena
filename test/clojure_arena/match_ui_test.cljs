(ns clojure-arena.match-ui-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure-arena.client :as client]
            [clojure-arena.game :as game]
            [clojure-arena.match :as match]
            [clojure-arena.match-countdown-test :as countdown]))

(use-fixtures :each
  (fn [run]
    (let [old (.getOwnPropertyDescriptor js/Object js/globalThis "document")]
      (aset js/globalThis "document" #js {})
      (try (run)
           (finally
             (if old (.defineProperty js/Object js/globalThis "document" old)
                 (js-delete js/globalThis "document")))))))

(defn element []
  (let [attributes (atom {})
        node #js {:textContent "" :className "" :style #js {} :children #js []
                  :setAttribute (fn [key value] (swap! attributes assoc key value))
                  :getAttribute (fn [key] (get @attributes key))}]
    (set! (.-appendChild node) (fn [child] (.push (.-children node) child)))
    (set! (.-replaceChildren node) (fn [] (set! (.-children node) #js [])))
    node))

(deftest match-clock-and-phase-labels-follow-the-snapshot
  (doseq [[seconds text] [[180 "03:00"] [179.975 "03:00"] [60 "01:00"] [0.025 "00:01"] [0 "00:00"]]]
    (is (= text (client/format-clock seconds))))
  (is (= "Esperando jugadores (mínimo 2)." (client/match-status-text match/initial-match)))
  (is (= "La partida comienza en 1 s" (client/match-status-text {:phase :countdown :countdown-seconds-left 0.025}))))

(deftest departed-winner-and-standings-use-captured-order-and-neutral-text
  (let [running (match/step (countdown/entrants) {} 3)
        finished (match/remove-player running :player-2)
        departed (match/remove-player finished :player-1)
        status (element) result (element) standings (element) players (element)
        doc #js {:getElementById (fn [id] (case id "match-status" status "match-result" result
                                                   "match-standings" standings "players" players nil))
                      :createElement (fn [_] (element))}]
    (with-redefs [js/document doc]
      (client/render-match! departed)
      (is (= "Ganador: player-1 · Último contendiente" (.-textContent result)))
      (is (= 0 (.-length (.-children players))))
      (let [rows (array-seq (.-children standings))]
        (is (= (mapv (comp name :player-id) (get-in departed [:match :result :standings]))
               (mapv #(.-textContent (aget (.-children %) 1)) rows)))
        (is (= "#22d3ee" (.-backgroundColor (.-style (aget (.-children (first rows)) 0))))))
      (is (nil? (.-color (.-style result))))
      (client/render-match! (assoc departed :match match/initial-match))
      (is (= "" (.-textContent result)))
      (is (= 0 (.-length (.-children standings)))))
    (is (= :player-1 (get-in departed [:match :result :winner-id])))))

(deftest observer-row-does-not-draw-a-body-or-change-the-roster
  (let [running (match/step (countdown/entrants) {} 3)
        world (match/add-player running :player-3 (game/make-player "#4ade80" 460 160))
        players (element) drawn (atom [])
        doc #js {:getElementById (fn [id] (when (= "players" id) players))
                      :createElement (fn [_] (element))}]
    (with-redefs [js/document doc
                  client/draw-background (fn [& _]) client/draw-platform (fn [& _])
                  client/draw-player (fn [_ player] (swap! drawn conj player))]
      (client/draw-world #js {:getContext (fn [_] #js {:fillText (fn [& _])})} world)
      (client/render-match! world)
      (is (= 2 (count @drawn)))
      (is (= 3 (.-length (.-children players))))
      (is (= "Observador · próxima partida"
             (.-textContent (aget (.-children (aget (.-children players) 2)) 4)))))
    (is (= (:match running) (:match world)))))

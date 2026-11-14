(ns clojure-arena.multi-platform-test
  (:require [clojure-arena.game :as game]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn solid [id x y width height]
  {:id id :type :solid :x x :y y :width width :height height})
(defn player [x y vx vy]
  (assoc (:player game/initial-world) :x x :y y :vx vx :vy vy :spawn-locked? false))
(defn near? [a b] (< (abs (- a b)) 1e-8))

(deftest earliest-solid-wins-regardless-of-collection-order
  (let [lower (solid :lower 0 280 800 16)
        upper (solid :upper 0 100 800 16)
        incoming (player 400 0 0 20000)
        a (game/collide-platforms incoming [lower upper] 0.025)
        b (game/collide-platforms incoming [upper lower] 0.025)]
    (is (= a b))
    (is (near? 84 (:y a)))
    (is (zero? (:vy a)))
    (is (= 0 (:y incoming)))))

(deftest support-and-ground-jump-use-a-non-first-solid
  (let [far (solid :a-far 0 400 100 16)
        top (solid :b-top 170 100 460 16)
        supported (assoc (player 400 84 0 0) :air-jumps-left 0)
        world {:platforms [far top] :player supported}
        jumped (game/step world {:jump? true} 0.025)]
    (is (= top (game/supporting-platform supported [far top])))
    (is (near? -395.5 (get-in jumped [:player :vy])))
    (is (zero? (get-in jumped [:player :air-jumps-left])))))

(deftest coincident-contacts-use-stable-id-order
  (let [a (solid :a 0 100 100 16)
        z (solid :z 100 100 100 16)
        incoming (player 100 0 0 1000)
        proposed (game/move-player incoming 0.1)
        contact #(game/earliest-contact incoming proposed % #{})]
    (is (= :a (get-in (contact [z a]) [:platform :id])))
    (is (= (contact [a z]) (contact [z a])))
    (is (= a (game/supporting-platform (player 100 84 0 0) [z a])))))

(deftest landing-continues-to-a-wall-in-the-same-tick
  (let [floor (solid :floor 0 100 400 16)
        wall (solid :wall 180 0 16 100)
        incoming (player 50 0 5000 5000)
        result (game/collide-platforms incoming [wall floor] 0.025)]
    (is (near? 84 (:y result)))
    (is (near? 164 (:x result)))
    (is (zero? (:vx result)))
    (is (zero? (:vy result)))
    (is (= result (game/collide-platforms incoming [floor wall] 0.025)))))

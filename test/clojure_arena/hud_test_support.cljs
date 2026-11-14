(ns clojure-arena.hud-test-support
  (:require [clojure.string :as string]
            [clojure-arena.game :as game]))

(def counter-keys [:created :text-writes :class-writes :style-writes
                   :attribute-writes :append-child :remove-child :replace-children
                   :canvas-writes])

(defn fresh-counters []
  (atom (zipmap counter-keys (repeat 0))))

(defn reset-counters! [counters]
  (reset! counters (zipmap counter-keys (repeat 0))))

(defn normalize-color [value]
  (let [value (str (or value ""))]
    (if (re-matches #"#[0-9a-fA-F]{6}" value)
      (let [red (js/parseInt (subs value 1 3) 16)
            green (js/parseInt (subs value 3 5) 16)
            blue (js/parseInt (subs value 5 7) 16)]
        (str "rgb(" red ", " green ", " blue ")"))
      value)))

(defn- define-tracked-property! [object key counters counter-key initial normalize]
  (let [value (atom initial)]
    (.defineProperty js/Object object key
                     #js {:configurable true
                          :enumerable true
                          :get (fn [] @value)
                          :set (fn [next-value]
                                 (swap! counters update counter-key inc)
                                 (reset! value (normalize next-value)))})
    object))

(defn- remove-child-from-array! [children child]
  (let [index (loop [index 0]
                (cond
                  (>= index (.-length children)) nil
                  (identical? child (aget children index)) index
                  :else (recur (inc index))))]
    (when (some? index)
      (.splice children index 1))))

(defn make-node [tag counters]
  (let [node #js {:tagName (string/upper-case tag)
                  :children (array)
                  :parentNode nil
                  :style #js {}
                  :attributes (atom {})}
        attrs (.-attributes node)
        class-list #js {}]
    (define-tracked-property! node "textContent" counters :text-writes "" str)
    (define-tracked-property! node "className" counters :class-writes "" str)
    (doseq [property ["backgroundColor" "color"]]
      (define-tracked-property! (.-style node) property counters :style-writes "" normalize-color))
    (doseq [[method f] [["appendChild"
                         (fn [child]
                           (when-let [old-parent (.-parentNode child)]
                             (remove-child-from-array! (.-children old-parent) child))
                           (.push (.-children node) child)
                           (set! (.-parentNode child) node)
                           (swap! counters update :append-child inc)
                           child)]
                        ["removeChild"
                         (fn [child]
                           (remove-child-from-array! (.-children node) child)
                           (set! (.-parentNode child) nil)
                           (swap! counters update :remove-child inc)
                           child)]
                        ["replaceChildren"
                         (fn [& children]
                           (swap! counters update :replace-children inc)
                           (doseq [child (array-seq (.-children node))]
                             (set! (.-parentNode child) nil))
                           (set! (.-length (.-children node)) 0)
                           (doseq [child children]
                             ((aget node "appendChild") child))
                           nil)]
                        ["setAttribute"
                         (fn [name value]
                           (swap! attrs assoc (str name) (str value))
                           (swap! counters update :attribute-writes inc)
                           nil)]
                        ["getAttribute" (fn [name] (get @attrs (str name) nil))]
                        ["removeAttribute"
                         (fn [name]
                           (swap! attrs dissoc (str name))
                           (swap! counters update :attribute-writes inc)
                           nil)]]]
      (aset node method f))
    (doseq [[method f] [["contains"
                         (fn [token]
                           (contains? (set (string/split (.-className node) #"\s+")) (str token)))]
                        ["add"
                         (fn [& tokens]
                           (let [current (string/split (string/trim (.-className node)) #"\s+")
                                 classes (into (vec (remove empty? current)) (map str tokens))]
                             (set! (.-className node) (string/join " " (distinct classes)))))]
                        ["remove"
                         (fn [& tokens]
                           (let [removed (set (map str tokens))
                                 current (string/split (string/trim (.-className node)) #"\s+")]
                             (set! (.-className node)
                                   (string/join " " (remove (fn [class] (or (empty? class) (contains? removed class))) current)))))]
                        ["toggle"
                         (fn [token & [force]]
                           (let [token (str token)
                                 present? ((aget class-list "contains") token)
                                 add? (if (some? force) (boolean force) (not present?))]
                             (if add?
                               ((aget class-list "add") token)
                               ((aget class-list "remove") token))
                             add?))]]]
      (aset class-list method f))
    (set! (.-classList node) class-list)
    node))

(defn make-dom []
  (let [counters (fresh-counters)
        nodes (atom {"players" (make-node "ul" counters)
                     "match-status" (make-node "p" counters)
                     "match-result" (make-node "p" counters)
                     "match-standings" (make-node "ol" counters)
                     "connection-status" (make-node "p" counters)})
        document #js {}]
    (aset document "getElementById" (fn [id] (get @nodes (str id) nil)))
    (aset document "createElement"
          (fn [tag]
            (swap! counters update :created inc)
            (make-node (str tag) counters)))
    {:document document :nodes nodes :counters counters}))

(defn replace-element! [{:keys [nodes counters]} id tag]
  (let [element (make-node tag counters)]
    (swap! nodes assoc id element)
    element))

(defn children [node]
  (vec (array-seq (.-children node))))

(defn player-rows [dom]
  (children (get @(:nodes dom) "players")))

(defn row-id [row]
  (.-textContent (aget (.-children row) 1)))

(defn row-for [dom id]
  (some #(when (= id (row-id %)) %) (player-rows dom)))

(defn row-fields [row]
  (let [fields (.-children row)]
    {:swatch (aget fields 0)
     :id (aget fields 1)
     :percentage (aget fields 2)
     :stocks (aget fields 3)
     :lifecycle (aget fields 4)}))

(defn sample-player [color x]
  (assoc (game/make-player color x 160)
         :spawn-locked? false
         :stocks 2
         :accumulated-percentage 25))

(defn sample-world []
  {:platforms []
   :players {:player-1 (sample-player "#22d3ee" 200)
             :player-2 (sample-player "#e11d48" 500)}
   :match {:id 8
           :phase :running
           :time-seconds-left 59.4
           :contenders {:player-1 {} :player-2 {}}
           :result {:winner-id :player-1
                    :winner-color "#22d3ee"
                    :reason :last-contender
                    :standings [{:player-id :player-1 :color "#22d3ee" :stocks 2
                                 :accumulated-percentage 25 :forfeited? false}
                                {:player-id :player-2 :color "#e11d48" :stocks 1
                                 :accumulated-percentage 100 :forfeited? true}]}}})

(defn context [counters]
  (let [ctx #js {:calls (atom []) :stateStack (atom [])}
        calls (.-calls ctx)
        stack (.-stateStack ctx)]
    (doseq [property ["fillStyle" "strokeStyle" "font" "textAlign" "textBaseline"]]
      (define-tracked-property! ctx property counters :canvas-writes "" str))
    (define-tracked-property! ctx "lineWidth" counters :canvas-writes 1 identity)
    (doseq [[method f] [["save"
                         (fn []
                           (swap! stack conj
                                  (into {} (map (fn [property] [property (aget ctx property)])
                                                ["fillStyle" "strokeStyle" "font" "textAlign" "textBaseline" "lineWidth"]))))]
                        ["restore"
                         (fn []
                           (when-let [saved (peek @stack)]
                             (swap! stack pop)
                             (doseq [[property value] saved]
                               (aset ctx property value))))]
                        ["fillText"
                         (fn [label x y]
                           (swap! calls conj {:kind :text :text label :x x :y y
                                              :fill-style (.-fillStyle ctx)
                                              :font (.-font ctx)
                                              :text-align (.-textAlign ctx)
                                              :text-baseline (.-textBaseline ctx)}))]
                        ["fillRect" (fn [& _] nil)]
                        ["strokeRect" (fn [& _] nil)]
                        ["beginPath" (fn [] nil)]
                        ["arc" (fn [& _] nil)]
                        ["fill" (fn [] nil)]
                        ["stroke" (fn [] nil)]]]
      (aset ctx method f))
    ctx))

(defn canvas [ctx]
  #js {:width 800 :height 480 :getContext (fn [_kind] ctx)})

(defn canvas-text [^js ctx]
  (->> @(.-calls ctx)
       (filter #(= :text (:kind %)))
       (mapv :text)))

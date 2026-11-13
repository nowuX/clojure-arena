(ns clojure-arena.client)

(defn init []
  (set! (.-textContent (.getElementById js/document "status"))
        "Clojure Arena está listo."))

# Guía breve de Clojure y ClojureScript

La simulación compartida está en `src/clojure_arena/game.cljc`; los eventos del navegador y el Canvas están en `src/clojure_arena/client.cljs`. Esta guía explica solo conceptos que aparecen en esos archivos. Para las reglas actuales, consulta [Simulación](simulation.md).

## Clojure compartido: `game.cljc`

### Datos y funciones

`def` nombra un valor; `defn` define una función. El mundo es un mapa con keywords como `:platform`, `:player` y `:x`:

```clojure
(def horizontal-speed 220)

(def initial-world
  {:platform {:x 170 :y 280 :width 460 :height 16 :color "#9c9c9c"}
   :player {:x 400 :y 160 :vx 0 :vy 0 :radius 16 :color "#22d3ee"}})
```

`step` recibe el mundo, las entradas y `dt` en segundos. Su argumento de entradas usa `{:keys [horizontal] :or {horizontal 0}}`: toma `:horizontal` del mapa, o usa cero si falta. La simulación produce mapas nuevos; no lee el reloj ni cambia el argumento original.

### Cálculo y composición

`->` pasa un valor por varias operaciones. `step` actualiza `:vx`, luego `:vy`, y finalmente llama a `collide-platform`. `update` calcula el nuevo valor de una clave del mapa. Por ejemplo, la función de fricción usa `let` para nombrar el cambio de velocidad y `cond` para elegir la regla según el signo:

```clojure
(defn apply-horizontal-friction [vx dt]
  (let [change (* horizontal-friction dt)]
    (cond
      (pos? vx) (max 0 (- vx change))
      (neg? vx) (min 0 (+ vx change))
      :else 0)))
```

`game.cljc` compila como Clojure y ClojureScript. El reader conditional de `sqrt` elige la operación de cada entorno:

```clojure
#?(:clj (Math/sqrt value)
   :cljs (js/Math.sqrt value))
```

Así, movimiento y colisiones son reutilizables sin importar APIs del navegador.

## ClojureScript del navegador: `client.cljs`

### Estado y eventos

`defonce` conserva referencias durante recargas de código en desarrollo. `atom` crea estado mutable controlado por el cliente, como `world` y `pressed-keys`. `@world` lee el valor actual; `swap!` aplica una función al valor actual y guarda el resultado.

`handle-key-down!`, `handle-key-up!` y `handle-blur!` manejan efectos del navegador. `horizontal-input` convierte `KeyD` y `KeyA` en `1`, `-1` o `0`; `read-inputs` lo entrega como `{:horizontal ...}`. Expresiones como `js/window`, `.addEventListener`, `.-code` y `(.preventDefault event)` acceden a objetos de JavaScript. `stop!` quita listeners, limpia teclas y cancela el frame pendiente.

### Tiempo y dibujo

`frame` usa `requestAnimationFrame`; `advance-world!` acumula tiempo y ejecuta ticks de 25 ms, pasando `0.025` segundos a `game/step`. Después, `draw-world` dibuja el mundo actual mediante `draw-background`, `draw-platform` y `draw-player` sobre Canvas. Dibujar no avanza la simulación.

`init` muestra en español el estado `Arena lista.`. Esa cadena pertenece a la presentación del cliente, no al mundo de juego.

## Recorrido de una tecla

Al mantener D, el listener agrega `KeyD` a `pressed-keys`. En el siguiente tick, `read-inputs` produce `{:horizontal 1}`. `game/step` devuelve el mundo siguiente, `world` lo guarda y el frame lo dibuja. Al soltar D, el input se vuelve neutral y `apply-horizontal-friction` reduce la velocidad. Al perder el foco, `handle-blur!` limpia las teclas.

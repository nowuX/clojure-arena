# Manual de lenguaje: Clojure y ClojureScript

Este documento enseña el **lenguaje** que usa Clojure Arena, no la arquitectura
del juego. Al terminarlo, una persona que nunca haya escrito Clojure debe poder
abrir cualquier archivo de `src/` o `test/` y entender qué hace cada forma.

Cada concepto aparece una sola vez, con el nombre que recibe en la comunidad,
un ejemplo mínimo y una referencia al código real donde se usa. Las reglas del
juego están en [Simulación](simulation.md), [Combate](combat.md) y
[Partidas](matches.md); el recorrido de componentes en
[Arquitectura](architecture.md).

## Cómo leer este manual

- Una forma como `(f x y)` es una llamada: el primer elemento es la función y el
  resto son argumentos.
- Los comentarios de los ejemplos usan `; =>` para mostrar el valor que produce
  la expresión. En Clojure 1.12 los mapas se imprimen con comas entre entradas;
  aquí se omiten para que se lean mejor.
- Salvo los de la sección 2, los resultados se comprobaron en Clojure 1.12.5
  sobre Java 21. ClojureScript usa números de JavaScript: donde un ejemplo JVM
  devuelve una razón exacta, ClojureScript devuelve un decimal. Los fragmentos
  de navegador describen propiedades del entorno y no se ejecutaron aquí.
- Los ejemplos que llaman a `game/`, `match/`, `protocol/` o `room/` necesitan
  un REPL de Clojure desde la raíz del proyecto:

```sh
clojure -M
```

```clojure
(require '[clojure-arena.game :as game])
```

- Los ejemplos con `js/`, Canvas o eventos solo funcionan en el navegador. No se
  pegan en un REPL de la JVM ni en una consola de JavaScript.
- `.clj` contiene código para la JVM, `.cljs` para JavaScript y `.cljc` para
  ambos. ClojureScript no se ejecuta en ese REPL: se compila con `just dev`,
  `just test` o `just build`.

1. [El lenguaje compartido](#1-el-lenguaje-compartido)
2. [ClojureScript y el navegador](#2-clojurescript-y-el-navegador)
3. [Clojure en el servidor](#3-clojure-en-el-servidor)
4. [Pruebas](#4-pruebas)
5. [Qué concepto ver en cada archivo](#5-qué-concepto-ver-en-cada-archivo)
6. [Cómo leer una forma desconocida](#6-cómo-leer-una-forma-desconocida)

## 1. El lenguaje compartido

Clojure y ClojureScript son el mismo lenguaje con dos destinos de compilación.
Todo lo de esta sección funciona en ambos salvo donde se indique.

### 1.1. Expresiones, comentarios y números

Una expresión devuelve siempre un valor; no hay sentencias. `;` abre un
comentario hasta el final de la línea.

```clojure
(+ 10 (* 2 3)) ; => 16
(/ 25 1000)    ; => 1/40
(/ 25 1000.0)  ; => 0.025
1e-9           ; => 1.0E-9
```

En la JVM, dividir dos enteros produce una **razón** exacta; con un decimal
produce un número de coma flotante. ClojureScript no tiene razones y devuelve
`0.025` en ambos casos. El juego usa `1e-9` como tolerancia: comparar decimales
con `=` casi nunca es fiable.

Referencia: [constantes de física](../src/clojure_arena/game.cljc#L3) y
[`step`](../src/clojure_arena/game.cljc#L452).

### 1.2. Namespaces y archivos

`ns` declara el espacio de nombres del archivo. `:require` carga otros
namespaces, `:as` crea un alias y `:refer` trae nombres sueltos. `:import` trae
clases de Java.

```clojure
(ns clojure-arena.client
  (:require [clojure-arena.game :as game]
            [clojure-arena.protocol :as protocol]))
```

El guion del namespace corresponde a un guion bajo en la ruta:
`clojure-arena.game` vive en `src/clojure_arena/game.cljc`. Con el alias, `game/step`
designa `step` dentro de ese namespace; sin alias habría que escribir el nombre
completo.

`:refer` se usa cuando se llaman pocas funciones y no se quiere prefijo, como
los middlewares de Ring en el servidor. La comilla de `'[clojure-arena.game :as game]`
en un `require` de REPL entrega el vector como dato en vez de evaluarlo.

Referencias: [`client.cljs`](../src/clojure_arena/client.cljs#L1),
[`:refer` en el servidor](../src/clojure_arena/server.clj#L9) y
[`:import` de http-kit](../src/clojure_arena/server.clj#L12).

### 1.3. Definir nombres y funciones

`def` asocia un nombre a un valor; `defn` define una función. El último valor del
cuerpo es el resultado. `let` da nombres locales a valores intermedios.

```clojure
(def gravity 980)

(defn gravity-change [dt]
  (let [change (* gravity dt)]
    change))

(gravity-change 0.025) ; => 24.5
```

Tres variantes que aparecen en el código:

- `defn-` declara una función privada del namespace:
  [`remaining-seconds`](../src/clojure_arena/game.cljc#L65).
- `^:private` añade la misma metadata a un `def` o `defonce`:
  [`offline-world`](../src/clojure_arena/client.cljs#L10).
- Un texto justo después del nombre es la **docstring**, la documentación de la
  función: [`outside-arena?`](../src/clojure_arena/game.cljc#L44).

Los sufijos son convenciones, no reglas del compilador: `?` en un predicado que
devuelve verdadero o falso (`active-player?`) y `!` en algo con efectos externos
(`send-input!`). `defonce` define solo si el nombre aún no existe; el cliente lo
usa para conservar los átomos cuando el desarrollo recarga el código
([`world`](../src/clojure_arena/client.cljs#L9)).

Dentro de un `let` se puede reutilizar un nombre: cada enlace nuevo oculta el
anterior y las expresiones siguientes ven el último. `step-melee` vuelve a
enlazar `remaining` y `player` sobre sus propios valores
([`step-melee`](../src/clojure_arena/game.cljc#L376)).

### 1.4. Valores, colecciones y claves

| Forma | Significado |
| --- | --- |
| `220`, `0.025`, `1e-9` | Número entero, decimal y notación científica. |
| `"KeyA"` | Cadena de texto. |
| `true`, `false`, `nil` | Booleanos y ausencia de valor. |
| `:x`, `:player-1` | **Keyword**: identificador que se usa como clave. |
| `{:x 400 :y 160}` | **Mapa** de claves a valores. |
| `[:main :left]` | **Vector**: colección ordenada con acceso por índice. |
| `#{"KeyA" "KeyD"}` | **Conjunto**: valores únicos sin orden. |
| `(:x :y)` | **Lista**: secuencia, y también la forma de una llamada. |

```clojure
(keys {:x 1 :y 2}) ; => (:x :y)
```

El orden de impresión de un mapa o un conjunto no es un orden de juego: no se
usa para decidir nada. Cuando el programa necesita un orden estable lo pide con
`sort-by`.

Referencias: [`make-player`](../src/clojure_arena/game.cljc#L19) y
[`neutral-input`](../src/clojure_arena/protocol.cljc#L9).

Los predicados de tipo comprueban la clase del valor y se usan para validar datos
que llegan de la red:

```clojure
(map? {})         ; => true
(vector? [])      ; => true
(set? #{})        ; => true
(string? "a")     ; => true
(number? 1.5)     ; => true
(integer? 1.5)    ; => false
(boolean? false)  ; => true
(keyword? :x)     ; => true
```

[`snapshot-message?`](../src/clojure_arena/protocol.cljc#L205) combina estos
predicados con `every?` para rechazar un mundo con la forma equivocada.

### 1.5. Verdad, `nil` y ausencia

En una condición solo `nil` y `false` son falsos. `0`, `""` y las colecciones
vacías son verdaderos.

```clojure
(if 0 :true :false)  ; => :true
(if [] :true :false) ; => :true
(some? 0)            ; => true
(nil? 0)             ; => false
(boolean nil)        ; => false
```

`some?` pregunta "¿no es `nil`?"; `nil?` pregunta lo contrario; `boolean` fuerza
el resultado a `true` o `false`. `boolean` aparece en
[`outside-arena?`](../src/clojure_arena/game.cljc#L44) para que una condición
compuesta devuelva un booleano limpio, y `some?` en
[`contender?`](../src/clojure_arena/match.cljc#L17).

### 1.6. Consultar datos

Una keyword puede actuar como función sobre un mapa. `get` permite un valor por
defecto y `get-in` recorre una ruta anidada. `contains?` comprueba presencia: en
un mapa pregunta por la clave, en un conjunto por el elemento y en un vector por
el índice.

```clojure
(:x {:x 400})                                          ; => 400
(get {:x 400} :vy 0)                                   ; => 0
(get-in {:players {:player-1 {:x 400}}} [:players :player-1 :x]) ; => 400
(contains? #{"KeyA" "KeyW"} "KeyW")                    ; => true
(contains? [:a] 0)                                     ; => true
```

Un valor `nil` presente no activa el valor por defecto de `get`:
`(get {:vy nil} :vy 0)` devuelve `nil`. Por eso la física suele escribir
`(:knockback-vx player 0)` cuando el campo puede faltar.

Un mapa también es una función de sus claves, y un conjunto de sus elementos:

```clojure
(map {:jump? true :drop? false} [:jump? :drop?]) ; => (true false)
(#{:solid :semisolid} :solid)                    ; => :solid
```

Esa segunda forma permite escribir `(remove assigned-colors protocol/player-colors)`
para quedarse con los colores libres
([`join-room`](../src/clojure_arena/room.clj#L25)).

### 1.7. Inmutabilidad: cómo avanza el mundo

`assoc` devuelve una colección con una clave reemplazada, `update` calcula su
nuevo valor con una función, y `assoc-in`/`update-in` hacen lo mismo sobre rutas
anidadas. `dissoc` retira una clave, `select-keys` conserva solo las indicadas y
`merge` combina mapas.

```clojure
(let [player {:vx 0 :facing :right}
      next-player (update player :vx + 22)]
  [player next-player])
; => [{:vx 0 :facing :right} {:vx 22 :facing :right}]

(assoc-in {:players {:player-1 {:x 400}}} [:players :player-1 :x] 410)
; => {:players {:player-1 {:x 410}}}

(select-keys {:x 400 :y 160 :vx 0} [:x :y]) ; => {:x 400 :y 160}
(merge {:stocks 3} {:stocks 2 :color "#22d3ee"}) ; => {:stocks 2 :color "#22d3ee"}
```

Ninguna de estas operaciones cambia el valor original: devuelven otro valor. Ese
es el mecanismo con el que `game/step` produce el mundo siguiente
([`move-player`](../src/clojure_arena/game.cljc#L100)) y con el que la sala
actualiza `:world` sin tocar la sala recibida
([`tick-room`](../src/clojure_arena/room.clj#L73)).

`update` y `update-in` aceptan argumentos extra para la función
(`(update-in {:n 1} [:n] + 10) ; => {:n 11}`), lo que se usa para fusionar una
entrada nueva sobre la anterior
([`apply-room-message`](../src/clojure_arena/room.clj#L89)).

### 1.8. Operar sobre colecciones

`conj` añade al final de un vector o a un conjunto respetando unicidad; `disj`
retira de un conjunto. `into` construye una colección desde otra. `set` y `vec`
convierten. `count`, `first` y `empty?` consultan tamaño, primer elemento y
vacuidad; `seq` devuelve `nil` para una colección vacía.

```clojure
(conj [:left] :right)                    ; => [:left :right]
(conj #{:player-2} :player-2 :player-4)  ; => #{:player-2 :player-4}
(disj #{"KeyA" "KeyW"} "KeyW")           ; => #{"KeyA"}
(into {} [[:x 400] [:y 160]])            ; => {:x 400 :y 160}
(seq [])                                 ; => nil
```

`(seq (:members next-room))` sirve como condición "hay alguien conectado"
([`tick-room`](../src/clojure_arena/room.clj#L73)), porque una colección vacía
sería verdadera pero `nil` es falso.

`into` con `map` y una función que devuelve parejas construye un mapa nuevo,
como la lista de contendientes al empezar la partida
([`start-match`](../src/clojure_arena/match.cljc#L98)).

### 1.9. Destructuración

Permite extraer valores sin repetir consultas. `:keys` enlaza claves de un mapa,
`:or` da valores por defecto, `:as` conserva la colección completa y un vector
separa posiciones.

```clojure
(let [{:keys [x vx] :or {vx 0}} {:x 400}
      [id direction] [:player-1 :right]]
  [id x vx direction])
; => [:player-1 400 0 :right]

(let [{:keys [x] :as player} {:x 400 :y 160}]
  (= player {:x 400 :y 160})) ; => true
```

Se usa en parámetros de función: [`resolve-contact`](../src/clojure_arena/game.cljc#L256)
recibe la posición y la normal del contacto ya separadas, y
[`step-player`](../src/clojure_arena/game.cljc#L388) extrae las cuatro acciones
con `:or`. Un enlace que empieza por `_` es un enlace normal que el autor marca
como no usado: `(fn [event] ...)` y `(fn [_event] ...)` reciben lo mismo.

### 1.10. Condiciones

| Forma | Comportamiento |
| --- | --- |
| `if` | Dos ramas; siempre evalúa una. |
| `if-not` | Igual que `if` con la condición negada. |
| `when` | Cuerpo solo si la condición es verdadera; si no, `nil`. |
| `when-not` | Como `when` con la condición negada. |
| `if-let` / `when-let` | Enlazan un valor y continúan solo si no es `nil`/`false`. |
| `when-some` | Como `when-let`, pero solo descarta `nil` (acepta `false`). |
| `cond` | Prueba condiciones en orden; sin `:else`, devuelve `nil`. |
| `case` | Elige por valor constante; admite grupos como `(:running :finished)`. |
| `and` / `or` | Cortocircuitan y devuelven el valor, no un booleano. |
| `do` | Evalúa varias expresiones y devuelve la última. |

```clojure
(if true :right :left)                                ; => :right
(when false :never)                                   ; => nil
(if-let [platform (first [])] (:id platform) :falling) ; => :falling
(when-let [id :player-1] (name id))                   ; => "player-1"
(cond (pos? -1) :positive (zero? 0) :zero :else :other) ; => :zero
(case :solid :solid :block :ignore)                    ; => :block
(case :running (:running :finished) :contender :other) ; => :contender
(or nil 0)                                             ; => 0
(and (= 1 1) (= 2 2) :ok)                              ; => :ok
```

`case` compara con constantes en tiempo de compilación y **lanza una excepción**
si no coincide y no hay rama final. Por eso [`match/step`](../src/clojure_arena/match.cljc#L164)
termina con `world` como resultado por defecto: una fase desconocida conserva el
mundo. `cond` sin coincidencia devuelve `nil`, que es lo que usa
[`result-for`](../src/clojure_arena/match.cljc#L56) para distinguir "aún no hay
resultado" de un resultado real.

`and` y `or` se usan como condición y como valor:
[`merge-pending-input`](../src/clojure_arena/protocol.cljc#L27) conserva una
acción pendiente con `or` aunque el mensaje nuevo traiga `false`.

`do` agrupa varias expresiones donde la forma admite una sola, por ejemplo en una
rama de `if` que dibuja con varios pasos
([`draw-platform`](../src/clojure_arena/client.cljs#L113)).

### 1.11. Números y comparaciones

```clojure
(min 10 4)          ; => 4
(max 10 4)          ; => 10
(abs -3)            ; => 3
(inc 1)             ; => 2
(dec 3)             ; => 2
(mod 65 60)         ; => 5
(<= 0 0.5 1)        ; => true
(== 184 184.0)      ; => true
(= 184 184.0)       ; => false
```

`<` y `<=` aceptan varios operandos, así que `(<= 0 0.5 1)` comprueba un
intervalo completo. `=` compara valores y distingue tipos; `==` compara solo
números y considera iguales `184` y `184.0`; `not=` es la negación de `=` y se
usa para descartar el propio atacante
([`melee-target-ids`](../src/clojure_arena/game.cljc#L356)). Las clasificaciones
usan `==` para detectar un empate real entre enteros y decimales
([`result-for`](../src/clojure_arena/match.cljc#L56)).

`zero?`, `pos?` y `neg?` responden por el signo y se leen mejor que una
comparación. En física también aparece `abs` para tolerancias
([`grounded?`](../src/clojure_arena/game.cljc#L130)).

### 1.12. Funciones como valores

Una función se puede pasar, devolver y guardar. `fn` la construye; `#(...)` es
la abreviatura con `%` como único argumento y `%1`, `%2` para varios.

```clojure
(mapv #(* % 2) [1 2 3])                     ; => [2 4 6]
((fn [& values] (apply + values)) 1 2 3)    ; => 6
((partial + 10) 5)                          ; => 15
((comp str :id) {:id :main})                ; => ":main"
((juxt :x :y) {:x 400 :y 160})              ; => [400 160]
(keep identity [nil {:time 0}])             ; => ({:time 0})
```

- `&` reúne los argumentos restantes y `apply` los entrega desde una colección.
- `partial` fija argumentos por la izquierda.
- `comp` compone de derecha a izquierda.
- `juxt` aplica varias funciones al mismo dato y devuelve un vector.
- `identity` devuelve su argumento; combinada con `keep`, descarta los `nil`.

Referencias: [`partial` al arrancar el publicador](../src/clojure_arena/server.clj#L144),
[`comp` y `juxt` al clasificar](../src/clojure_arena/match.cljc#L49) y
[`#()` en un filtro](../src/clojure_arena/game.cljc#L137).

### 1.13. Recorrer y acumular colecciones

| Operación | Uso |
| --- | --- |
| `map`, `mapv` | Transformar; `map` es perezosa y `mapv` devuelve vector. |
| `filter`, `filterv` | Conservar los que cumplen un predicado. |
| `remove` | Descartar los que cumplen un predicado. |
| `keep` | Transformar y descartar los `nil`. |
| `reduce` | Combinar elementos con un acumulador. |
| `reduce-kv` | Igual, pero recibe acumulador, clave y valor de un mapa. |
| `sort-by` | Ordenar por el valor que calcula una función. |
| `every?` | Comprobar que todos cumplen una condición. |
| `frequencies` | Contar apariciones. |
| `fnil` | Sustituir `nil` por un valor inicial antes de llamar. |

```clojure
(mapv :id (sort-by :time [{:id :later :time 0.8} {:id :first :time 0.2}]))
; => [:first :later]
(reduce + 0 [10 10 10])                                        ; => 30
(reduce-kv (fn [total _id p] (+ total (:accumulated-percentage p)))
           0
           {:player-1 {:accumulated-percentage 20}
            :player-2 {:accumulated-percentage 30}})            ; => 50
(frequencies [:right :left :right])                            ; => {:right 2 :left 1}
(update {} :hits (fnil inc 0))                                 ; => {:hits 1}
```

`reduce` sobre un mapa recorre **parejas** `[clave valor]`, como en
[`apply-melee-hits`](../src/clojure_arena/game.cljc#L417);
`reduce-kv` recibe los tres valores sueltos, como en
[`step`](../src/clojure_arena/game.cljc#L452). `fnil` se usa para acumular sobre
una clave que puede no existir: `(update hits target-id (fnil conj []) ...)`
crea el vector vacío la primera vez.

Las secuencias de `map` y `filter` son **perezosas**: se calculan a medida que se
consumen y se imprimen entre paréntesis. `mapv` y `filterv` construyen un vector
de inmediato, que es lo que se guarda en el estado.

### 1.14. Encadenar transformaciones

`->` inserta el valor anterior como primer argumento de la siguiente forma; `->>`
lo inserta como último. `cond->` aplica cada transformación solo si su condición
es verdadera. Son macros: reescriben el código antes de ejecutarlo.

```clojure
(-> {:vx 0}
    (update :vx + 22)
    (assoc :facing :right)) ; => {:vx 22 :facing :right}

(cond-> {:status :closed}
  true  (assoc :close-code 1000)
  false (assoc :close-reason "x")) ; => {:status :closed :close-code 1000}
```

`->` describe bien una transición de estado y por eso aparece en casi todas las
funciones de `game.cljc` y `match.cljc`. `->>` encaja cuando la colección va al
final, como en [`ranked-standings`](../src/clojure_arena/match.cljc#L49).
`cond->` construye el estado de cierre del socket añadiendo datos opcionales
([`onclose`](../src/clojure_arena/transport.cljs#L213)).

### 1.15. Repetición con `loop` y `recur`

`loop` fija valores iniciales y `recur` vuelve al inicio con valores nuevos. Debe
estar en posición final: después de `recur` no queda trabajo pendiente, así que
no se acumulan marcos de pila.

```clojure
(loop [remaining 3 total 0]
  (if (zero? remaining)
    total
    (recur (dec remaining) (+ total 2)))) ; => 6

(take 3 (iterate inc 0)) ; => (0 1 2)
```

[`collide-platforms`](../src/clojure_arena/game.cljc#L293) repite con cuatro
valores: jugador, tiempo restante, contactos disponibles e IDs de salida. El
cliente repite el tiempo acumulado en pasos de 25 ms
([`advance-world!`](../src/clojure_arena/client.cljs#L321)).

### 1.16. Arities y funciones puras

Una función puede tener varias **arities**: distintas cantidades de argumentos.
El cálculo de impulso usa dos para la configuración normal y tres para recibir
una explícita.

```clojure
(game/melee-knockback-impulse [:right] 10)                                    ; => {:dvx 184.0 :dvy -138.0}
(game/melee-knockback-impulse [:left] 50 {:base-impulse 100 :percentage-scale 2}) ; => {:dvx -160.0 :dvy -120.0}
```

Una función **pura** depende solo de sus argumentos y no produce efectos. Eso
permite probarla sin servidor y repetir el mismo cálculo. `game/step` recibe
mundo, entradas y `dt`, y devuelve el mundo siguiente sin guardarlo ni dibujarlo
([`step`](../src/clojure_arena/game.cljc#L452)). El reloj, los sockets y el DOM
quedan en las capas con efectos.

Otras funciones con varias arities: [`deliver-effects!`](../src/clojure_arena/server.clj#L28),
[`websocket-response`](../src/clojure_arena/server.clj#L39) y
[`make-driver`](../src/clojure_arena/heartbeat.clj#L17).

### 1.17. Átomos

Un átomo guarda una referencia mutable a un valor inmutable. `@` (o `deref`) lo
lee, `reset!` lo reemplaza y `swap!` guarda el resultado de aplicar una función
al valor actual. `compare-and-set!` cambia el valor solo si aún es el esperado y
devuelve si lo consiguió.

```clojure
(let [state (atom {:vx 0})
      before @state]
  (reset! state {:vx 10})
  (swap! state update :vx + 12)
  [before @state]) ; => [{:vx 0} {:vx 22}]

(let [pending? (atom false)]
  [(compare-and-set! pending? false true)  ; => true
   @pending?                               ; => true
   (compare-and-set! pending? false true)]) ; => false
```

La función que recibe `swap!` puede ejecutarse de nuevo si hay una actualización
concurrente, así que no debe tener efectos externos. El cliente guarda con
`swap!` el resultado de `game/step`, que es puro
([`advance-world!`](../src/clojure_arena/client.cljs#L321)); el planificador
reserva un tick pendiente con `compare-and-set!`
([`make-driver`](../src/clojure_arena/ticker.clj#L8)).

`add-watch` instala un observador que recibe clave, referencia, valor anterior y
valor nuevo **después** de instalar la actualización. El cliente lo usa para
copiar el snapshot recibido al mundo que dibuja
([`add-watch`](../src/clojure_arena/client.cljs#L30)).

### 1.18. Agentes

Un agente guarda un valor y una cola de acciones. `send` encola una función que
recibe el valor anterior y devuelve el siguiente; las acciones de un mismo agente
se ejecutan una a una, en orden. Quien envía no espera.

```clojure
(let [room-agent (room/make-room (fn [effects] nil))]
  (room/enqueue-room-message! room-agent {:type :join :connection-token :demo})
  (await-for 1000 room-agent)
  (get-in @room-agent [:room :members :demo]))
; => {:player-id :player-1 :color :cyan}
```

Aquí `await-for` espera como máximo un segundo a que se vacíe la cola; `@` lee
el estado instalado sin esperar. `agent-error` informa de un fallo y
`shutdown-agents` cierra los hilos del ejecutor al terminar un proceso o unas
pruebas ([`server_test.clj`](../test/clojure_arena/server_test.clj#L211)).

La sala devuelve `{:room ... :effects ...}` y su observador entrega los efectos
ya confirmados ([`make-room`](../src/clojure_arena/room.clj#L123)). Así una misma
acción encola el cambio de estado y la respuesta que hay que enviar, sin que la
transición pura toque sockets.

Diferencia práctica: un átomo aplica `swap!` de inmediato en el hilo que llama;
un agente procesa en su propio hilo y serializa las escrituras.

### 1.19. Excepciones

`throw` interrumpe; `ex-info` construye una excepción con datos; `ex-data` los
recupera. `try`/`catch` decide qué hacer con el fallo y `finally` ejecuta la
limpieza siempre.

```clojure
(try
  (throw (ex-info "boom" {:code :invalid-message}))
  (catch Exception error
    (:code (ex-data error)))) ; => :invalid-message
```

`finally` aparece donde hay que restaurar algo aunque falle el cuerpo: las
propiedades del contexto de Canvas ([`draw-player`](../src/clojure_arena/client.cljs#L138))
y el apagado del servidor ([`stop!`](../src/clojure_arena/server.clj#L167)).

`binding` cambia una variable dinámica durante un bloque; el servidor imprime
errores en la salida de error con `(binding [*out* *err*] ...)`
([`broadcast-world!`](../src/clojure_arena/server.clj#L17)).

En ClojureScript, `catch` puede usar `:default` como comodín:
`(catch :default _ ...)`. El protocolo comparte el `try` y elige el tipo con un
condicional de lectura ([`read-client-message`](../src/clojure_arena/protocol.cljc#L241)).

### 1.20. Texto, keywords y expresiones regulares

```clojure
(str 10 "%")                     ; => "10%"
(str :right)                     ; => ":right"
(str nil)                        ; => ""
(keyword (str "player-" 3))      ; => :player-3
(name :player-1)                 ; => "player-1"
(namespace :player-1)            ; => nil
(re-matches #"player-[1-9][0-9]*" "player-12") ; => "player-12"
(re-matches #"player-[1-9][0-9]*" "player-0")  ; => nil
(parse-long "8082")              ; => 8082
(clojure.string/blank? " ")      ; => true
```

`name` devuelve el texto de una keyword sin su calificador y `namespace` el
calificador, o `nil` si no lo tiene. `re-matches` exige que **toda** la cadena
coincida: no basta con que contenga el patrón. El validador de IDs exige además
que no haya calificador ([`player-id?`](../src/clojure_arena/protocol.cljc#L45)).

`parse-long` devuelve `nil` si el texto no es un entero; el servidor lo usa para
detectar un puerto inválido ([`parse-options`](../src/clojure_arena/server.clj#L126)).

`str` concatena y convierte; `pr-str` obtiene la representación con sintaxis de
datos, con comillas incluidas, y es lo que se envía por el socket.

Como `(str nil)` es `""`, un texto opcional se compone con
`(str base (when condición extra))`. El estado de conexión usa ese patrón para
añadir servidor, código de cierre e identidad solo cuando existen
([`set-state!`](../src/clojure_arena/transport.cljs#L39)).

### 1.21. Interoperabilidad con la JVM

`(Clase.)` construye un objeto, `(.metodo objeto args)` llama a un método y
`(Clase/metodoEstatico args)` a uno estático. `import` permite usar el nombre
corto de una clase.

```clojure
(Object.)                     ; un objeto nuevo, útil solo por su identidad
(Runtime/getRuntime)          ; método estático
(System/nanoTime)             ; tiempo monotónico en nanosegundos
```

- `(Object.)` identifica una conexión: cada llamada crea un objeto distinto, y
  solo `identical?` los reconoce
  ([`websocket-response`](../src/clojure_arena/server.clj#L44)).
- `reify` implementa una interfaz Java sin crear una clase con nombre; el
  planificador lo usa para su `ThreadFactory`
  ([`start!`](../src/clojure_arena/ticker.clj#L31)).
- `doto` encadena llamadas sobre el mismo objeto y devuelve el objeto
  (`(doto (Thread. runnable "clojure-arena-ticker") (.setDaemon true))`).
- `(Clase/CAMPO)` accede a una constante estática: `TimeUnit/MILLISECONDS`.
- Las **type hints** (`^Runnable`, `^bytes`, `^AsyncChannel`) eligen la
  sobrecarga correcta de un método y evitan reflexión
  ([`scheduleWithFixedDelay`](../src/clojure_arena/ticker.clj#L42),
  [`Arrays/equals`](../src/clojure_arena/heartbeat.clj#L40)).
- `locking` usa el monitor de un objeto para que un bloque no se ejecute a la
  vez desde dos hilos ([`make-driver`](../src/clojure_arena/ticker.clj#L8)).

Un array de Java es mutable y `=` no compara su contenido:
`(Arrays/equals ^bytes a ^bytes b)` sí, y `^bytes` selecciona esa sobrecarga.
`ByteBuffer/allocate` y `.putLong` construyen el desafío de 8 bytes del
heartbeat ([`pulse!`](../src/clojure_arena/heartbeat.clj#L59)).

### 1.22. Hilos, planificadores y colas

`Executors/newSingleThreadScheduledExecutor` crea un planificador de un hilo y
`.scheduleWithFixedDelay` repite una tarea con un retraso contado desde el final
de la invocación anterior. `.cancel`, `.shutdownNow` y `.awaitTermination`
detienen y esperan.

`ArrayBlockingQueue` es una cola con capacidad fija: `.offer` inserta sin
esperar, `.poll` retira sin esperar y `.take` espera hasta que haya un elemento.

```clojure
(import '[java.util.concurrent ArrayBlockingQueue])
(let [queue (ArrayBlockingQueue. 1)]
  [(.offer queue {:tick 1}) ; => true
   (.poll queue)            ; => {:tick 1}
   (.poll queue)])          ; => nil
```

El publicador usa capacidad uno: reemplaza el snapshot pendiente por el más
reciente mientras otro hilo envía
([`publish!`](../src/clojure_arena/publication.clj#L36)). El hilo del temporizador
nunca modifica el mundo: solo envía una acción al agente
([`make-driver`](../src/clojure_arena/ticker.clj#L8)).

Los hilos creados son daemon, para no impedir el cierre del proceso, y cada
`stop!` espera con plazo y lanza `ex-info` si no termina
([`ticker/stop!`](../src/clojure_arena/ticker.clj#L50)).

### 1.23. Metadatos y keywords cualificadas

`with-meta` asocia datos a un valor sin cambiar el valor; `meta` los lee. El
manejador HTTP guarda ahí su agente, sus conexiones y su publicador
([`make-handler`](../src/clojure_arena/server.clj#L104)).

```clojure
(meta (with-meta (fn [x] x) {:role :handler})) ; => {:role :handler}
```

`::` delante de un nombre produce una keyword cualificada con el namespace
actual: `::room-agent` en `clojure-arena.server` es
`:clojure-arena.server/room-agent`. Así las claves internas no chocan con las
del juego, que son datos serializables.

### 1.24. EDN: datos sin código

EDN es la notación de datos de Clojure: mapas, keywords, números y vectores. Un
lector EDN reconstruye valores **sin evaluar nada**; por eso los mensajes de red
se leen así y no con `eval`.

```clojure
(pr-str {:type :ping})                 ; => "{:type :ping}"
(protocol/decode "{:type :ping}")      ; => {:type :ping}
(protocol/reply-to-message "{:type :ping}") ; => {:type :pong}
```

`decode` abre un lector sobre el texto, pide un valor con
`(edn/read {:eof eof} reader)` y comprueba que el siguiente valor sea el marcador
de fin; si sobra texto lanza `(ex-info "Expected one EDN value" {})`
([`decode`](../src/clojure_arena/protocol.cljc#L231)).

El marcador `eof` es un objeto nuevo y `identical?` compara **identidad**, no
contenido: como ningún valor leído puede ser ese mismo objeto, el lector
reconoce el fin de entrada aunque el mensaje sea `nil`.
`identical?` también decide si un callback pertenece al socket o al contenedor
DOM actual ([`start!`](../src/clojure_arena/transport.cljs#L128)).

### 1.25. Condicionales de lectura

En un `.cljc`, `#?` elige una forma al leer el archivo: `:clj` para la JVM y
`:cljs` para JavaScript. No son dos cálculos: solo se compila una rama.

```clojure
(defn sqrt [value]
  #?(:clj (Math/sqrt value)
     :cljs (js/Math.sqrt value)))
```

`Math/sqrt` es de Java y `js/Math.sqrt` de JavaScript. El mismo mecanismo elige
el lector EDN, la validación de enteros y el `catch`
([`protocol.cljc`](../src/clojure_arena/protocol.cljc#L3),
[`safe-nonnegative-integer?`](../src/clojure_arena/protocol.cljc#L33),
[`read-client-message`](../src/clojure_arena/protocol.cljc#L263)) y los
namespaces de prueba ([`game_test.cljc`](../test/clojure_arena/game_test.cljc#L5)).

### 1.26. Configuración del proyecto

`deps.edn` declara rutas y dependencias con versiones fijas, y `:aliases` define
formas de ejecución. `:main-opts` con `-m` llama a la función `-main` de un
namespace.

```edn
{:server {:main-opts ["-m" "clojure-arena.server"]}}
```

Así `clojure -M:server` arranca el servidor, que es lo que hace `just server`.
Las recetas de `justfile` solo agrupan comandos externos; no son lenguaje. La
configuración del compilador de ClojureScript y los comandos están en
[Desarrollo](development.md).

## 2. ClojureScript y el navegador

ClojureScript compila a JavaScript. Añade interoperabilidad con objetos del
navegador; la sintaxis y las colecciones son las mismas de la sección 1.

### 2.1. Acceder a JavaScript

`js/` accede a un global, `.-propiedad` lee una propiedad, `.metodo` llama a un
método y `set!` asigna una propiedad.

```clojure
(let [element (.getElementById js/document "status")]
  (set! (.-textContent element) "Arena lista."))
```

`js/Math.ceil`, `js/Number.isInteger` y `js/Math.floor` llaman a funciones
globales. `format-clock` las combina con `.padStart` para escribir `mm:ss`
([`format-clock`](../src/clojure_arena/client.cljs#L262)). `.-repeat` indica
repetición automática de una tecla y `.-code` el código físico de la tecla
([`handle-key-down!`](../src/clojure_arena/client.cljs#L65)). `.-data` contiene
el texto recibido por el socket
([`onmessage`](../src/clojure_arena/transport.cljs#L149)).

### 2.2. Constructores y objetos nativos

`(js/URL. texto)` y `(js/WebSocket. url)` construyen objetos del navegador con
`new`. Un `URL` expone `.-origin`, `.-host`, `.-protocol` y `.-searchParams`, y
`.get` de `searchParams` lee un parámetro de la dirección
([`websocket-url`](../src/clojure_arena/transport.cljs#L14)). `#js {...}`
construye un objeto o array de JavaScript, sin conversión a mapa persistente; los
objetos anidados necesitan su propio `#js`.

```clojure
(let [socket #js {:url url :readyState 0 :sent #js [] :closeCount 0}]
  (.-readyState socket)) ; => 0
```

`#js` se usa en las pruebas para simular sockets, elementos y documentos
([`connection_diagnostics_test.cljs`](../test/clojure_arena/connection_diagnostics_test.cljs#L12)).
Un objeto JavaScript es mutable; un mapa de Clojure no. Por eso los dobles de
prueba guardan contadores en átomos dentro del objeto.

### 2.3. DOM

`.createElement`, `.appendChild`, `.replaceChildren`, `.setAttribute` y
`.-textContent` construyen y actualizan el panel. `doseq` recorre para producir
efectos y devuelve `nil`; admite `:when` para filtrar.

```clojure
(doseq [platform platforms] (draw-platform canvas platform))
```

El panel crea una fila por jugador y la reutiliza
([`render-players!`](../src/clojure_arena/client.cljs#L241)). `set-text!` escribe
solo si el texto cambia, para no tocar el DOM sin necesidad.

### 2.4. Canvas

El contexto 2D es mutable: se dibuja cambiando sus propiedades. `try`/`finally`
restaura las que se modifican temporalmente.

```clojure
(let [context (.getContext canvas "2d")]
  (set! (.-fillStyle context) "#101010")
  (.fillRect context 0 0 (.-width canvas) (.-height canvas)))
```

`.beginPath`, `.arc` y `.fill` dibujan círculos; `.strokeRect` los bordes de las
plataformas semisólidas ([`draw-platform`](../src/clojure_arena/client.cljs#L113)).
Dibujar no avanza la simulación: solo lee el mundo recibido.

### 2.5. Eventos y callbacks

Un callback es una función que otra API llamará después; se entrega como valor,
sin paréntesis. `.addEventListener` y `.removeEventListener` necesitan la misma
función para quitar el listener.

```clojure
(.addEventListener js/window "keydown" handle-key-down!)
(.removeEventListener js/window "keydown" handle-key-down!)
```

El objeto de evento es el argumento: `.-button` vale `0` para el botón principal
del puntero ([`handle-pointer-down!`](../src/clojure_arena/client.cljs#L88)).
Perder el foco limpia teclas y pulsos pendientes
([`handle-blur!`](../src/clojure_arena/client.cljs#L95)).

### 2.6. Animación y `partial`

`js/requestAnimationFrame` pide una llamada antes del siguiente repintado y
devuelve un identificador que permite cancelarla. `partial` fija argumentos por
la izquierda; el navegador añade el timestamp restante.

```clojure
(js/requestAnimationFrame (partial frame canvas nil 0))
```

[`frame`](../src/clojure_arena/client.cljs#L336) se reprograma a sí mismo
guardando el nuevo identificador en un átomo; `js/cancelAnimationFrame` cancela
el pendiente al detener el cliente ([`stop!`](../src/clojure_arena/client.cljs#L348)).

### 2.7. Recarga de código y `defonce`

`defonce` inicializa un átomo solo la primera vez, así que recargar el código del
cliente no borra el mundo ni los listeners registrados. Los hooks de Shadow CLJS
llaman a `stop!` antes de reemplazar el código y a `init` después, y así no se
acumulan listeners ni animaciones ([`shadow-cljs.edn`](../shadow-cljs.edn)).

`init` empieza llamando a `stop!` ([`init`](../src/clojure_arena/client.cljs#L364)):
arrancar dos veces debe dejar un solo cliente activo.

### 2.8. `deref` y tipos de referencia

`@` sobre una promesa de JavaScript no funciona; solo `deref` y `@` leen
referencias de Clojure (átomos, agentes, futuros). HTTP de http-kit devuelve una
promesa al cerrar y el servidor espera con `@stopped`
([`stop!`](../src/clojure_arena/server.clj#L167)). En ClojureScript, una promesa
de JavaScript se maneja con `.then` o `async`/`await`, que este código no usa.

## 3. Clojure en el servidor

El servidor usa la JVM, `http-kit` y Ring. No añade sintaxis nueva: añade
bibliotecas cuyos datos son mapas.

### 3.1. Ring: peticiones y respuestas como mapas

Una petición es un mapa con `:request-method`, `:uri` y cabeceras; una respuesta
es un mapa con `:status`, `:headers` y `:body`.

```clojure
{:status 200
 :headers {"Content-Type" "text/plain; charset=utf-8"}
 :body "ok\n"}
```

`routes` recibe la petición ya desestructurada en sus parámetros
([`routes`](../src/clojure_arena/server.clj#L95)). Un **middleware** recibe un
manejador y devuelve otro que añade una responsabilidad; `->` los compone:
`wrap-file` sirve archivos de `public/`, `wrap-content-type` asigna el tipo y
`wrap-head` adapta las peticiones HEAD
([`make-handler`](../src/clojure_arena/server.clj#L104)).

### 3.2. http-kit: canales WebSocket

`http/run-server` arranca el socket y `http/as-channel` registra callbacks de
apertura, recepción y cierre. `http/send!` envía texto.

```clojure
(http/send! channel (pr-str message))
```

Los callbacks son funciones normales; el canal es un objeto Java. `broadcast-world!`
recorre los destinatarios con `doseq` y envía el mismo texto precalculado
([`broadcast-world!`](../src/clojure_arena/server.clj#L17)).

Una clase interna de Java se nombra con `$` en Clojure:
`(Frame$PingFrame. payload)` construye el frame de ping que el heartbeat envía
por el canal ([`make-handler`](../src/clojure_arena/server.clj#L104)).

### 3.3. `-main`, argumentos y cierre

`-main` recibe los argumentos de la línea de comandos como secuencia: `[& args]`
reúne todos. `(Thread. #(stop! server))` construye un hilo con una función y
`(.addShutdownHook (Runtime/getRuntime) hilo)` lo registra para Ctrl+C.

`server-join` mantiene el proceso vivo mientras el servidor escucha; `finally`
garantiza la limpieza al salir ([`-main`](../src/clojure_arena/server.clj#L187)).

## 4. Pruebas

### 4.1. `deftest`, `is` y `testing`

`deftest` define una prueba, `is` comprueba una expresión y `testing` agrupa
comprobaciones con una descripción. Si una expresión dentro de `is` falla, la
prueba informa de la forma que falló. `is` acepta un texto como segundo argumento
para explicar el caso cuando falla.

En un archivo de prueba con `clojure.test` cargado:

```clojure
(deftest gravity-adds-vertical-speed
  (testing "un tick aplica gravedad sin tocar el mundo original"
    (is (= 24.5 (get-in (game/step game/initial-world {} 0.025)
                        [:players :player-1 :vy])))
    (is (= 0 (get-in game/initial-world [:players :player-1 :vy])))))
```

Los archivos `.cljc` eligen el framework con un condicional de lectura: la JVM
usa `clojure.test` y ClojureScript `cljs.test`, que se importa con
`:refer-macros` porque `deftest` e `is` son macros
([`game_test.cljc`](../test/clojure_arena/game_test.cljc#L5)).
`testing` se usa para nombrar el caso dentro de una prueba más larga
([`protocol_test.cljc`](../test/clojure_arena/protocol_test.cljc#L68)).

### 4.2. Fixtures y `with-redefs`

`use-fixtures :each` envuelve cada prueba con una función que prepara y limpia
el entorno ([`client_test.cljs`](../test/clojure_arena/client_test.cljs#L33)).
`with-redefs` sustituye una definición durante un bloque y la restaura al salir;
así una prueba puede sustituir `game/step` por un doble
([`network_client_test.cljs`](../test/clojure_arena/network_client_test.cljs#L483))
sin cambiar el código de producción.

### 4.3. Generar escenarios

Las pruebas construyen series de estados con `iterate`, `take`, `nth` y
`take-while`, y combinan `dotimes` y `while` para repeticiones con contador. Así
se observa una trayectoria de 40 ticks sin escribir 40 pasos.

```clojure
(let [counter (atom 0)]
  (dotimes [_ 3] (swap! counter inc))
  @counter) ; => 3
```

Un caso real repite `client/init` cuatro veces y comprueba que solo queda un
listener registrado ([`client_test.cljs`](../test/clojure_arena/client_test.cljs#L93)).

`dotimes` repite un cuerpo un número fijo de veces; `while` repite mientras una
condición sea verdadera ([`server_session_test.clj`](../test/clojure_arena/server_session_test.clj#L43)).
`iterate` devuelve una secuencia perezosa de aplicaciones repetidas y `nth`
toma una posición. Los helpers de prueba reutilizan `game/step` con unas pocas
entradas ([`fixtures.cljc`](../test/clojure_arena/fixtures.cljc#L14)).

Otras utilidades que aparecen en las pruebas, con resultados comprobados:

```clojure
(range 0 5 2)              ; => (0 2 4)
(repeat 3 0)               ; => (0 0 0)
(zipmap [:a :b] (repeat 0)) ; => {:a 0 :b 0}
(take-while pos? [3 2 0 -1]) ; => (3 2)
(update-vals {:a 1} inc)    ; => {:a 2}
```

`zipmap` cruza dos secuencias en un mapa; `repeat` genera valores infinitos que
hay que limitar o consumir con `zipmap`/`take`. `update-vals` transforma los
valores de un mapa conservando sus claves.

Para esperar trabajo asíncrono de un agente, las pruebas usan `await-for` y
comprueban `agent-error` ([`room_test.clj`](../test/clojure_arena/room_test.clj#L21)).
Las pruebas de concurrencia lanzan `future` y leen su resultado con
`(deref futuro 4000 ::timeout)`, que devuelve el marcador si no termina a tiempo
([`room_match_regression_test.clj`](../test/clojure_arena/room_match_regression_test.clj#L53)).

## 5. Qué concepto ver en cada archivo

| Archivo | Conceptos que ilustra |
| --- | --- |
| [`game.cljc`](../src/clojure_arena/game.cljc) | mapas inmutables, `cond`, `case`, `loop`/`recur`, destructuración, `->`, `#?`, `juxt`, `fnil` |
| [`match.cljc`](../src/clojure_arena/match.cljc) | `case` por fase, `->>`, `into` con `map`, `juxt`, `==`, `cond` sin `:else` |
| [`protocol.cljc`](../src/clojure_arena/protocol.cljc) | validación con `every?`, igualdad de conjuntos, `re-matches`, `#?`, EDN, `try`/`catch` |
| [`room.clj`](../src/clojure_arena/room.clj) | `cond`, `contains?`, `remove` con conjunto, `into`, agentes y `add-watch` |
| [`server.clj`](../src/clojure_arena/server.clj) | mapas de Ring, middleware, `doseq`, `locking`, metadata, `try`/`finally` |
| [`ticker.clj`](../src/clojure_arena/ticker.clj) | `reify`, `doto`, type hints, planificador, `compare-and-set!` |
| [`publication.clj`](../src/clojure_arena/publication.clj) | cola acotada, `.take`/`.offer`/`.poll`, hilo consumidor |
| [`heartbeat.clj`](../src/clojure_arena/heartbeat.clj) | `reduce-kv` con acumulador compuesto, `ByteBuffer`, arrays y `^bytes` |
| [`client.cljs`](../src/clojure_arena/client.cljs) | `defonce`, átomos, eventos, Canvas, DOM, `partial`, `js/` |
| [`transport.cljs`](../src/clojure_arena/transport.cljs) | `js/URL`, `js/WebSocket`, callbacks, `cond->`, `identical?`, mensajes |
| [`fixtures.cljc`](../test/clojure_arena/fixtures.cljc) y `*_test.clj*` | `deftest`, `is`, `testing`, fixtures, `with-redefs`, `iterate` |

## 6. Cómo leer una forma desconocida

1. Mira el primer elemento: si es una función, es una llamada; si es `def`,
   `defn`, `let`, `if` o similar, es una forma especial o una macro y tiene
   reglas propias.
2. Si el primer elemento es una keyword, un mapa o un conjunto, está actuando
   como función de consulta.
3. Busca `{...}` en los parámetros: es destructuración, no un mapa que se pasa
   tal cual.
4. Reescribe `->`, `->>` y `cond->` si el orden de los argumentos no se ve claro.
5. Antes de suponer que algo muta, comprueba si usa `atom`, `swap!`, `reset!` o
   una API de Java. El resto del código devuelve valores nuevos.
6. Copia la forma a un REPL con datos pequeños y observa el resultado.

## Formas que este código no usa

Para que no parezcan lagunas: aquí no hay `defrecord`, `defprotocol`, `deftype`,
`defmulti`/`defmethod`, referencias STM (`ref`, `dosync`), `core.async`,
transductores ni macros propias. Si aparecen al leer otros proyectos, son
extensiones del lenguaje, no requisitos para entender este.

Al añadir código nuevo, incorpora cada concepto una sola vez a este manual, con
un ejemplo breve y su referencia actual. Las reglas del juego y la arquitectura
permanecen en sus documentos.

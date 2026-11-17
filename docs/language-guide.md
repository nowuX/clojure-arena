# Manual de lenguaje: Clojure y ClojureScript

Este documento enseña el **lenguaje** Clojure y ClojureScript desde cero. No
describe la arquitectura de ningún programa concreto ni depende de código
externo: cada ejemplo es autocontenido y se puede probar tal cual.

El objetivo es que alguien que nunca haya escrito Clojure pueda leer código
Clojure o ClojureScript y entender qué hace cada forma, sin necesidad de conocer
antes el proyecto donde aparece.

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
- Todos los ejemplos de la JVM se pueden pegar en un REPL (`clojure`) sin cargar
  ningún namespace. Los de la sección 2 necesitan un navegador y un compilador
  de ClojureScript.
- `.clj` contiene código para la JVM, `.cljs` para JavaScript y `.cljc` para
  ambos. El mismo `.cljc` se compila dos veces, una por destino.

1. [El lenguaje compartido](#1-el-lenguaje-compartido)
2. [ClojureScript y el navegador](#2-clojurescript-y-el-navegador)
3. [Clojure en el servidor](#3-clojure-en-el-servidor)
4. [Pruebas](#4-pruebas)
5. [Cómo leer una forma desconocida](#5-cómo-leer-una-forma-desconocida)
6. [Formas fuera de esta guía](#6-formas-fuera-de-esta-guía)

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
`0.025` en ambos casos. Por eso los cálculos con decimales necesitan una
tolerancia: comparar dos resultados con `=` casi nunca es fiable. El valor
`1e-9` es una tolerancia habitual, porque es mayor que el error de redondeo y
mucho menor que cualquier diferencia significativa.

### 1.2. Namespaces y archivos

`ns` declara el espacio de nombres del archivo. `:require` carga otros
namespaces, `:as` crea un alias, `:refer` trae nombres sueltos y `:import` trae
clases de Java.

```clojure
(ns ejemplo.app
  (:require [clojure.string :as str]
            [clojure.set :refer [union]])
  (:import [java.util.concurrent ArrayBlockingQueue]))
```

El guion del namespace corresponde a un guion bajo en la ruta: `ejemplo.app`
vive en `src/ejemplo/app.clj`. Con el alias, `str/join` designa `join` dentro de
ese namespace; sin alias habría que escribir el nombre completo.

`:refer` se usa cuando se llaman pocas funciones y no se quiere prefijo. La
comilla de `'[ejemplo.app :as app]` en un `require` de REPL entrega el vector
como dato en vez de evaluarlo.

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

Tres variantes habituales:

- `defn-` declara una función privada del namespace.
- `^:private` añade la misma metadata a un `def` o `defonce`.
- Un texto justo después del nombre es la **docstring**, la documentación de la
  función:

```clojure
(defn outside-bounds?
  "Devuelve true si la posición queda fuera de los límites indicados."
  [x y bounds]
  (or (< x (:left bounds)) (> x (:right bounds))
      (< y (:top bounds)) (> y (:bottom bounds))))
```

Los sufijos son convenciones, no reglas del compilador: `?` en un predicado que
devuelve verdadero o falso (`active?`) y `!` en algo con efectos externos
(`send-input!`). `defonce` define solo si el nombre aún no existe; se usa para
conservar estado cuando el entorno recarga el código durante el desarrollo.

Dentro de un `let` se puede reutilizar un nombre: cada enlace nuevo oculta el
anterior y las expresiones siguientes ven el último.

```clojure
(let [speed 10
      speed (* speed 2)]
  speed) ; => 20
```

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

El orden de impresión de un mapa o un conjunto no es un orden lógico ni de
presentación: no se usa para decidir nada. Cuando el programa necesita un orden
estable lo pide con `sort-by`.

Los predicados de tipo comprueban la clase del valor y se usan para validar datos
que llegan de fuera:

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

Un valor también puede comprobarse con `instance?` y el nombre de su clase
(`(instance? String "a") ; => true`), útil al recibir objetos de Java.

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
el resultado a `true` o `false`. Esto importa al traducir a otros lenguajes: un
`if` de Clojure no equivale a comparar con cero ni con cadena vacía.

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
`(get {:score nil} :score 0)` devuelve `nil`. Por eso conviene escribir
`(:score player 0)` cuando el campo puede faltar; esa forma de keyword con valor
por defecto es equivalente a `(get player :score 0)`.

Un mapa también es una función de sus claves, y un conjunto de sus elementos:

```clojure
(map {:jump? true :drop? false} [:jump? :drop?]) ; => (true false)
(#{:solid :semisolid} :solid)                    ; => :solid
```

Esa segunda forma permite filtrar una colección con otra:
`(remove assigned-colors all-colors)` se queda con los elementos de
`all-colors` que no están en `assigned-colors`.

### 1.7. Inmutabilidad: cómo avanza un estado

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
(merge {:lives 3} {:lives 2 :color "#22d3ee"}) ; => {:lives 2 :color "#22d3ee"}
```

Ninguna de estas operaciones cambia el valor original: devuelven otro valor. Ese
es el mecanismo con el que una función pura produce el estado siguiente sin
modificar el anterior.

`update` y `update-in` aceptan argumentos extra para la función
(`(update-in {:n 1} [:n] + 10) ; => {:n 11}`), lo que sirve para combinar un
valor nuevo con el anterior sin consultarlo antes.

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

`(seq coll)` sirve como condición "hay algo", porque una colección vacía sería
verdadera pero `nil` es falso.

`into` con `map` y una función que devuelve parejas construye un mapa nuevo:

```clojure
(into {} (map (fn [id] [id {:lives 3}]) [:player-1 :player-2]))
; => {:player-1 {:lives 3} :player-2 {:lives 3}}
```

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

Se usa sobre todo en parámetros de función, donde evita una línea de consultas:

```clojure
(defn platform? [{:keys [type width]}]
  (and (= :solid type) (pos? width)))
```

Un enlace que empieza por `_` es un enlace normal que el autor marca como no
usado: `(fn [event] ...)` y `(fn [_event] ...)` reciben lo mismo.

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
si no coincide y no hay rama final. Por eso es habitual cerrar un `case` de
fases con un resultado por defecto que conserva el estado recibido:

```clojure
(defn settle [phase state]
  (case phase
    :running state
    (:finished :abandoned) (assoc state :closed? true)
    state))
```

`cond` sin coincidencia devuelve `nil`, lo que permite distinguir "aún no hay
resultado" de un resultado real.

`and` y `or` se usan como condición y como valor. `or` conserva una acción
pendiente aunque el mensaje nuevo traiga `false`, porque `false` es falso para
`or`:

```clojure
(or false true)  ; => true
(or nil 0)       ; => 0
```

`do` agrupa varias expresiones donde la forma admite una sola, por ejemplo en una
rama de `if` que ejecuta varios pasos.

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
números y considera iguales `184` y `184.0`; `not=` es la negación de `=`.
`zero?`, `pos?` y `neg?` responden por el signo y se leen mejor que una
comparación; `abs` se usa para tolerancias.

Para comparar decimales calculados se usa una diferencia menor que un umbral:

```clojure
(< (abs (- 0.30000000000000004 0.3)) 1e-9) ; => true
```

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

`comp` y `juxt` son la base de los criterios de orden compuestos:
`(juxt :lives :percentage)` devuelve una pareja que `sort-by` compara en orden.

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
(reduce-kv (fn [total _id p] (+ total (:percentage p)))
           0
           {:player-1 {:percentage 20}
            :player-2 {:percentage 30}})                       ; => 50
(frequencies [:right :left :right])                            ; => {:right 2 :left 1}
(update {} :hits (fnil inc 0))                                 ; => {:hits 1}
```

`reduce` sobre un mapa recorre **parejas** `[clave valor]`; `reduce-kv` recibe
los tres valores sueltos. `fnil` se usa para acumular sobre una clave que puede
no existir: `(update hits target (fnil conj []) item)` crea el vector vacío la
primera vez.

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

`->` describe bien una transición de estado: cada paso recibe el resultado
anterior como primer argumento. `->>` encaja cuando la colección va al final,
porque así se leen las operaciones de secuencia en orden:

```clojure
(->> {:player-2 {:lives 1} :player-1 {:lives 3}}
     (map (fn [[id entry]] (assoc entry :id id)))
     (sort-by (comp - :lives))
     (map :id)
     vec)
; => [:player-1 :player-2]
```

`cond->` construye un valor con datos opcionales; cada condición se evalúa una
vez, en orden.

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

`iterate` devuelve una secuencia perezosa de aplicaciones repetidas y `nth` toma
una posición, así que `(nth (iterate f x) 40)` calcula cuarenta pasos sin
escribirlos. Es la forma habitual de avanzar una simulación en las pruebas.

### 1.16. Arities y funciones puras

Una función puede tener varias **arities**: distintas cantidades de argumentos.
Una arity puede delegar en otra para no duplicar el cuerpo.

```clojure
(defn salute
  ([name] (salute "Hola" name))
  ([greeting name] (str greeting ", " name)))

(salute "Ada")            ; => "Hola, Ada"
(salute "Buenas" "Ada")   ; => "Buenas, Ada"
```

Una función **pura** depende solo de sus argumentos y no produce efectos. Eso
permite probarla sin servidor y repetir el mismo cálculo:

```clojure
(defn apply-gravity [vy dt]
  (+ vy (* 980 dt)))

(apply-gravity 0 0.025) ; => 24.5
```

El reloj, los sockets y el DOM quedan en las capas con efectos; la lógica pura
recibe valores y devuelve valores.

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
concurrente, así que no debe tener efectos externos. `compare-and-set!` permite
reservar un recurso: solo el primer hilo que cambia `false` a `true` continúa.

`add-watch` instala un observador que recibe clave, referencia, valor anterior y
valor nuevo **después** de instalar la actualización:

```clojure
(def changes (atom []))
(def state (atom 0))
(add-watch state ::log (fn [_ _ old new] (swap! changes conj [old new])))
(swap! state inc)
@changes ; => [[0 1]]
```

### 1.18. Agentes

Un agente guarda un valor y una cola de acciones. `send` encola una función que
recibe el valor anterior y devuelve el siguiente; las acciones de un mismo agente
se ejecutan una a una, en orden. Quien envía no espera.

```clojure
(def counter (agent 0))
(send counter inc)
(await-for 1000 counter) ; => true
@counter                 ; => 1
```

`await-for` espera como máximo un tiempo a que se vacíe la cola; `@` lee el
estado instalado sin esperar. `agent-error` informa de un fallo y
`shutdown-agents` cierra los hilos del ejecutor al terminar un proceso o unas
pruebas.

Diferencia práctica: un átomo aplica `swap!` de inmediato en el hilo que llama;
un agente procesa en su propio hilo y serializa las escrituras. Por eso un agente
sirve para dueño único de un estado con mensajes, y un átomo para un valor
compartido de lectura rápida.

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

`finally` aparece donde hay que liberar algo aunque falle el cuerpo:

```clojure
(defn with-open-file [path use!]
  (let [file (java.io.FileReader. path)]
    (try
      (use! file)
      (finally
        (.close file)))))
```

La misma estructura restaura una propiedad temporal del contexto de dibujo en
ClojureScript: se lee antes del `try`, se modifica dentro y se devuelve a su
valor anterior en el `finally`.

`ex-message` lee el texto de una excepción y `ex-cause` la anterior de una
cadena. `binding` cambia una variable dinámica durante un bloque; el ejemplo más
común redirige la salida de error:

```clojure
(binding [*out* *err*]
  (println "Diagnóstico"))
```

En ClojureScript, `catch` puede usar `:default` como comodín:
`(catch :default _ ...)`, porque no hay clases de excepción de la JVM.

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
coincida: no basta con que contenga el patrón. Un patrón admite grupos,
alternancias y cuantificadores como en cualquier motor de expresiones regulares.

`parse-long` devuelve `nil` si el texto no es un entero, así que sirve para
detectar un dato inválido sin lanzar una excepción.

`str` concatena y convierte; `pr-str` obtiene la representación con sintaxis de
datos, con comillas incluidas, y es lo que se envía por un socket. Como
`(str nil)` es `""`, un texto opcional se compone con
`(str base (when condición extra))`.

### 1.21. Interoperabilidad con la JVM

`(Clase.)` construye un objeto, `(.metodo objeto args)` llama a un método y
`(Clase/metodoEstatico args)` a uno estático. `import` permite usar el nombre
corto de una clase.

```clojure
(Object.)                     ; un objeto nuevo, útil solo por su identidad
(Runtime/getRuntime)          ; método estático
(System/nanoTime)             ; tiempo monotónico en nanosegundos
```

- `(Object.)` identifica algo: cada llamada crea un objeto distinto, y solo
  `identical?` los reconoce como el mismo.
- `reify` implementa una interfaz Java sin crear una clase con nombre:

```clojure
(reify Runnable
  (run [_] (println "trabajo")))
```

- `doto` encadena llamadas sobre el mismo objeto y devuelve el objeto:

```clojure
(doto (Thread. #(println "hola") "ejemplo")
  (.setDaemon true)
  (.start))
```

- Las **type hints** (`^Runnable`, `^bytes`, `^String`) eligen la sobrecarga
  correcta de un método y evitan reflexión:

```clojure
(import '[java.util.concurrent Executors])
(let [executor (Executors/newSingleThreadExecutor)]
  (.execute executor ^Runnable (fn [] (println "tarea")))
  (.shutdown executor))
```

- `locking` usa el monitor de un objeto para que un bloque no se ejecute a la
  vez desde dos hilos:

```clojure
(let [gate (Object.)
      state (atom 0)]
  (locking gate
    (swap! state inc))
  @state) ; => 1
```

- `(Clase/CAMPO)` accede a una constante estática: `TimeUnit/MILLISECONDS`.

Un array de Java es mutable y `=` no compara su contenido:

```clojure
(Arrays/equals ^bytes (byte-array [1 2]) ^bytes (byte-array [1 2])) ; => true
(= (byte-array [1]) (byte-array [1]))                              ; => false
(alength ^bytes (byte-array [1 2 3]))                              ; => 3
```

`ByteBuffer/allocate` y `.putLong` construyen paquetes binarios cuando hay que
enviar bytes por un canal.

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

Una cola de capacidad uno sirve para conservar solo el último valor pendiente:
`.offer` sobre una cola llena devuelve `false` sin bloquear, y el productor puede
descartar el anterior antes de reintentar.

Los hilos creados suelen marcarse como daemon para no impedir el cierre del
proceso, y cada parada espera con plazo y lanza `ex-info` si no termina.

### 1.23. Metadatos y keywords cualificadas

`with-meta` asocia datos a un valor sin cambiar el valor; `meta` los lee.

```clojure
(meta (with-meta (fn [x] x) {:role :handler})) ; => {:role :handler}
```

Un manejador HTTP puede guardar ahí sus dependencias en vez de usar variables
globales.

`::` delante de un nombre produce una keyword cualificada con el namespace
actual: en `ejemplo.app`, `::room-agent` es `:ejemplo.app/room-agent`. Así las
claves internas no chocan con las de datos externos, que deben ser
serializables.

### 1.24. EDN: datos sin código

EDN es la notación de datos de Clojure: mapas, keywords, números y vectores. Un
lector EDN reconstruye valores **sin evaluar nada**; por eso los mensajes de red
se leen así y no con `eval`.

```clojure
(require '[clojure.edn :as edn])
(pr-str {:type :ping})        ; => "{:type :ping}"
(edn/read-string "{:type :ping}") ; => {:type :ping}
```

`read-string` de `clojure.edn` lee un único valor. Para comprobar que no sobra
texto se usa el lector con un marcador de fin:

```clojure
(let [eof (Object.)
      reader (java.io.PushbackReader.
              (java.io.StringReader. "{:a 1} {:b 2}"))]
  [(edn/read {:eof eof} reader)
   (edn/read {:eof eof} reader)]) ; => [{:a 1} {:b 2}]
```

El marcador `eof` es un objeto nuevo y `identical?` compara **identidad**, no
contenido: como ningún valor leído puede ser ese mismo objeto, el lector
reconoce el fin de entrada aunque el mensaje sea `nil`.

### 1.25. Condicionales de lectura

En un `.cljc`, `#?` elige una forma al leer el archivo: `:clj` para la JVM y
`:cljs` para JavaScript. No son dos cálculos: solo se compila una rama.

```clojure
(defn sqrt [value]
  #?(:clj (Math/sqrt value)
     :cljs (js/Math.sqrt value)))
```

`Math/sqrt` es de Java y `js/Math.sqrt` de JavaScript. El mismo mecanismo sirve
para elegir un lector EDN, una validación de enteros o el `catch` de un `.cljc`.

### 1.26. Configuración y arranque

EDN también describe la configuración de las herramientas de Clojure. `deps.edn`
declara rutas y dependencias con versiones fijas, y `:aliases` define formas de
ejecución:

```edn
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.5"}}
 :aliases {:dev {:extra-paths ["dev"]}}}
```

`:main-opts` con `-m` llama a la función `-main` de un namespace:

```edn
{:aliases {:server {:main-opts ["-m" "ejemplo.server"]}}}
```

Así `clojure -M:server` arranca el programa con ese alias. Las recetas de un
`justfile` o un `Makefile` solo agrupan comandos externos; no son lenguaje.

## 2. ClojureScript y el navegador

ClojureScript compila a JavaScript. Añade interoperabilidad con objetos del
navegador; la sintaxis y las colecciones son las mismas de la sección 1.

### 2.1. Acceder a JavaScript

`js/` accede a un global, `.-propiedad` lee una propiedad, `.metodo` llama a un
método y `set!` asigna una propiedad.

```clojure
(let [element (.getElementById js/document "status")]
  (set! (.-textContent element) "Listo."))
```

`js/Math.ceil`, `js/Number.isInteger` y `js/Math.floor` llaman a funciones
globales. Una propiedad calculada se lee con `aget`/`aset` o con `goog.object`,
no con `.-`.

### 2.2. Constructores y objetos nativos

`(js/URL. texto)` y `(js/WebSocket. url)` construyen objetos del navegador con
`new`. Un `URL` expone `.-origin`, `.-host`, `.-protocol` y `.-searchParams`, y
`.get` de `searchParams` lee un parámetro de la dirección.

`#js {...}` construye un objeto o array de JavaScript, sin conversión a mapa
persistente; los objetos anidados necesitan su propio `#js`.

```clojure
(let [socket #js {:url "ws://localhost/ws" :readyState 0 :sent #js []}]
  (.-readyState socket)) ; => 0
```

Un objeto JavaScript es mutable; un mapa de Clojure no. Si un objeto nativo
necesita un contador mutable, se guarda un átomo dentro del objeto.

### 2.3. DOM

`.createElement`, `.appendChild`, `.replaceChildren`, `.setAttribute` y
`.-textContent` construyen y actualizan el panel. `doseq` recorre para producir
efectos y devuelve `nil`; admite `:when` para filtrar.

```clojure
(doseq [platform platforms :when (= :solid (:type platform))]
  (.appendChild panel (make-row platform)))
```

Escribir solo cuando el valor cambia evita repintados innecesarios:

```clojure
(defn set-text! [element text]
  (when (not= (.-textContent element) text)
    (set! (.-textContent element) text)))
```

### 2.4. Canvas

El contexto 2D es mutable: se dibuja cambiando sus propiedades. `try`/`finally`
restaura las que se modifican temporalmente.

```clojure
(let [context (.getContext canvas "2d")]
  (set! (.-fillStyle context) "#101010")
  (.fillRect context 0 0 (.-width canvas) (.-height canvas)))
```

`.beginPath`, `.arc` y `.fill` dibujan círculos; `.strokeRect` dibuja el borde
de un rectángulo. Dibujar no modifica el estado: solo lo lee.

### 2.5. Eventos y callbacks

Un callback es una función que otra API llamará después; se entrega como valor,
sin paréntesis. `.addEventListener` y `.removeEventListener` necesitan la misma
función para quitar el listener.

```clojure
(.addEventListener js/window "keydown" handle-key-down!)
(.removeEventListener js/window "keydown" handle-key-down!)
```

El objeto de evento es el argumento: `.-button` vale `0` para el botón principal
del puntero y `.-code` contiene el código físico de la tecla. Perder el foco
suele limpiar teclas y pulsos pendientes.

### 2.6. Animación y `partial`

`js/requestAnimationFrame` pide una llamada antes del siguiente repintado y
devuelve un identificador que permite cancelarla. `partial` fija argumentos por
la izquierda; el navegador añade el timestamp restante.

```clojure
(js/requestAnimationFrame (partial draw canvas))
```

Una función de animación se reprograma a sí misma guardando el nuevo
identificador en un átomo; `js/cancelAnimationFrame` cancela el pendiente al
detener la aplicación.

### 2.7. Recarga de código y `defonce`

`defonce` inicializa un átomo solo la primera vez, así que recargar el código de
la aplicación no borra el estado ni los listeners registrados. Los hooks del
compilador llaman a una función de parada antes de reemplazar el código y a la
de arranque después, y así no se acumulan listeners ni animaciones.

### 2.8. `deref` y tipos de referencia

`@` sobre una promesa de JavaScript no funciona; solo `deref` y `@` leen
referencias de Clojure (átomos, agentes, futuros). Una promesa de JavaScript se
maneja con `.then` o `async`/`await`, que no forman parte de este lenguaje base.

```clojure
(.then (js/fetch "/health")
       (fn [response] (.-status response)))
```

## 3. Clojure en el servidor

Clojure en la JVM suele combinarse con `http-kit` y Ring. No añade sintaxis
nueva: añade bibliotecas cuyos datos son mapas.

### 3.1. Ring: peticiones y respuestas como mapas

Una petición es un mapa con `:request-method`, `:uri` y cabeceras; una respuesta
es un mapa con `:status`, `:headers` y `:body`.

```clojure
{:status 200
 :headers {"Content-Type" "text/plain; charset=utf-8"}
 :body "ok\n"}
```

Un **middleware** recibe un manejador y devuelve otro que añade una
responsabilidad; `->` los compone:

```clojure
(def handler
  (-> routes
      (wrap-file "public")
      wrap-content-type
      wrap-head))
```

Así `wrap-file` sirve archivos, `wrap-content-type` asigna el tipo y `wrap-head`
adapta las peticiones HEAD sin tocar la lógica de rutas.

### 3.2. http-kit: canales WebSocket

`http/run-server` arranca el socket y `http/as-channel` registra callbacks de
apertura, recepción y cierre. `http/send!` envía texto.

```clojure
(http/send! channel (pr-str message))
```

Los callbacks son funciones normales; el canal es un objeto Java. Recorrer los
destinatarios con `doseq` y enviar el mismo texto precalculado evita serializar
una vez por conexión.

Una clase interna de Java se nombra con `$` en Clojure:
`(Frame$PingFrame. payload)` construye el frame de ping que el heartbeat envía
por el canal.

### 3.3. `-main`, argumentos y cierre

`-main` recibe los argumentos de la línea de comandos como secuencia: `[& args]`
reúne todos. `(Thread. #(stop! server))` construye un hilo con una función y
`(.addShutdownHook (Runtime/getRuntime) hilo)` lo registra para Ctrl+C.

Un `promise` o una espera bloqueante mantiene el proceso vivo mientras el
servidor escucha; `finally` garantiza la limpieza al salir.

## 4. Pruebas

### 4.1. `deftest`, `is` y `testing`

`deftest` define una prueba, `is` comprueba una expresión y `testing` agrupa
comprobaciones con una descripción. Si una expresión dentro de `is` falla, la
prueba informa de la forma que falló. `is` acepta un texto como segundo argumento
para explicar el caso cuando falla.

```clojure
(defn step-speed [player dt]
  (update player :vy + (* 980 dt)))

(deftest gravity-adds-vertical-speed
  (testing "un tick suma velocidad sin modificar el valor original"
    (is (= 24.5 (:vy (step-speed {:vy 0} 0.025))))
    (is (= 0 (:vy {:vy 0})))))
```

Los archivos `.cljc` eligen el framework con un condicional de lectura: la JVM
usa `clojure.test` y ClojureScript `cljs.test`, que se importa con
`:refer-macros` porque `deftest` e `is` son macros.

### 4.2. Fixtures y `with-redefs`

`use-fixtures :each` envuelve cada prueba con una función que prepara y limpia el
entorno:

```clojure
(def state (atom nil))

(defn- fixture [test-fn]
  (reset! state :fresh)
  (try
    (test-fn)
    (finally (reset! state nil))))

(use-fixtures :each fixture)
```

`with-redefs` sustituye una definición durante un bloque y la restaura al salir;
así una prueba puede sustituir una función lenta o con efectos por un doble sin
cambiar el código de producción:

```clojure
(defn fetch-remote [_] {:status :slow})

(with-redefs [fetch-remote (fn [_] {:status :ok})]
  (is (= :ok (:status (fetch-remote "x")))))
```

### 4.3. Generar escenarios

Las pruebas construyen series de estados con `iterate`, `take`, `nth` y
`take-while`, y combinan `dotimes` y `while` para repeticiones con contador. Así
se observa una trayectoria de 40 pasos sin escribirlos uno a uno.

```clojure
(let [counter (atom 0)]
  (dotimes [_ 3] (swap! counter inc))
  @counter) ; => 3
```

`dotimes` repite un cuerpo un número fijo de veces; `while` repite mientras una
condición sea verdadera. `iterate` devuelve una secuencia perezosa de
aplicaciones repetidas y `nth` toma una posición, lo que permite describir una
trayectoria con una sola expresión.

Otras utilidades que aparecen en las pruebas, con resultados comprobados:

```clojure
(range 0 5 2)              ; => (0 2 4)
(repeat 3 0)               ; => (0 0 0)
(zipmap [:a :b] (repeat 0)) ; => {:a 0 :b 0}
(take-while pos? [3 2 0 -1]) ; => (3 2)
(update-vals {:a 1} inc)    ; => {:a 2}
```

`zipmap` cruza dos secuencias en un mapa; `repeat` genera valores repetidos que
hay que limitar o consumir con `zipmap`/`take`. `update-vals` transforma los
valores de un mapa conservando sus claves.

Para esperar trabajo asíncrono de un agente, una prueba usa `await-for` y
comprueba `agent-error`. Para concurrencia se lanzan `future` y se lee su
resultado con `(deref futuro 4000 ::timeout)`, que devuelve el marcador si no
termina a tiempo:

```clojure
(defn- await-agent! [agent]
  (is (await-for 4000 agent) "las acciones encoladas deben terminar")
  (is (nil? (agent-error agent)) "el agente debe seguir sano"))
```

## 5. Cómo leer una forma desconocida

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

## 6. Formas fuera de esta guía

Este manual cubre el lenguaje que aparece en código de aplicación típico. No
trata `defrecord`/`deftype`, `defprotocol`, `defmulti`/`defmethod`, referencias
STM (`ref`, `dosync`, `alter`), `core.async`, transductores ni macros propias.
Son parte del lenguaje y de su ecosistema, pero no son necesarias para leer el
código que este manual describe; si aparecen, conviene consultar la referencia
oficial de Clojure después de dominar estas bases.

Al ampliar esta guía, incorpora cada concepto una sola vez, con un ejemplo
mínimo y verificado. Los ejemplos deben poder ejecutarse sin cargar el código de
ningún proyecto concreto.

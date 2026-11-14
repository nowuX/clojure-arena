# Guía breve de Clojure y ClojureScript

La simulación compartida está en `src/clojure_arena/game.cljc`; los eventos y el dibujo del navegador están en `src/clojure_arena/client.cljs`. Esta guía cubre los conceptos que usa el juego. Consulta [Simulación](simulation.md) para las reglas.

## Clojure compartido: `game.cljc`

### Mapas y funciones

`def` nombra un valor y `defn` define una función. El mundo es un mapa; sus claves son keywords como `:player`, `:spawn-locked?` y `:air-jumps-left`:

```clojure
{:player {:x 400 :y 160 :vx 0 :vy 0 :radius 16
          :spawn-locked? true :air-jumps-left 1}}
```

`step` recibe mundo, entradas y `dt` en segundos. El argumento `{:keys [horizontal jump? drop?] :or {horizontal 0 jump? false drop? false}}` extrae valores del mapa y fija sus valores por defecto. La simulación devuelve mapas nuevos; no consulta el navegador ni cambia el argumento original.

### Condiciones y valores nuevos

Las funciones con `?`, como `grounded?`, responden una pregunta. `supporting-platform` devuelve el mapa de plataforma si el jugador está apoyado arriba o `nil` si no lo está. `if`, `and` y `not` combinan condiciones; `cond` elige una rama según varios casos. `assoc` devuelve un mapa con un valor reemplazado y `update` calcula un valor nuevo para una clave.

`apply-jump` usa el estado del jugador, las plataformas y el pulso `jump?`. Con controles desbloqueados, acepta el salto terrestre si `grounded?` es verdadero y conserva la recarga aérea; en el aire acepta un solo salto si `:air-jumps-left` es positivo y reduce el contador. En otro caso deja el jugador sin lanzamiento. `step` aplica esta decisión antes de sumar gravedad.

### Composición, bucles y portabilidad

`let` da nombres locales a valores intermedios. El macro `->` pasa el valor
por operaciones sucesivas; por ejemplo, `move-player` devuelve un mapa nuevo:

```clojure
(-> player
    (update :x + (* (:vx player) dt))
    (update :y + (* (:vy player) dt)))
```

`loop` y `recur` repiten cálculos con valores nuevos, sin cambiar el mundo
recibido. `collide-platforms` los usa para continuar el tiempo restante tras
cada contacto, con un máximo de cuatro contactos por tick. `keep` descarta
candidatos nulos y `sort-by` ordena contactos por tiempo.

Un archivo `.cljc` se puede compilar en Clojure y ClojureScript. `sqrt` usa
un reader conditional para seleccionar la biblioteca de cada entorno:

```clojure
#?(:clj (Math/sqrt value)
   :cljs (js/Math.sqrt value))
```

## ClojureScript del navegador: `client.cljs`

### Átomos y eventos

`defonce` conserva referencias entre recargas de desarrollo; `atom` contiene una referencia mutable del cliente; `@world` la lee y `swap!` guarda el resultado de aplicar una función al valor actual. `reset!` reemplaza el contenido completo de un átomo. `pressed-keys` representa teclas mantenidas en un conjunto (`#{}`); `conj`, `disj` y `contains?` agregan, quitan y consultan sus elementos. `pending-jump` y `pending-drop` conservan pulsos independientes de W y S hasta un tick. `read-inputs` lee el estado sin consumirlo; `consume-inputs!` lee y limpia ambos pulsos.

Un evento W o S nuevo y no repetido marca su pulso. Soltar la tecla quita su estado mantenido, pero no borra el pulso capturado. `handle-blur!` limpia las teclas y ambos pulsos; `stop!` también lo hace mediante esa función. Así, un toque rápido puede llegar al siguiente tick y un pulso no se repite en los ticks siguientes.

`js/window`, `.addEventListener`, `.-code` y `(.preventDefault event)` son interoperabilidad con JavaScript. `frame` usa `requestAnimationFrame`; `advance-world!` ejecuta ticks de 25 ms. Después, `draw-world` llama a las funciones que pintan el fondo, la plataforma y el jugador. Dibujar no avanza la simulación. El estado visible de inicio es `Arena lista.`.

## Recorrido de A/D y W

Al mantener D, el evento agrega `KeyD` al conjunto y `horizontal-input` produce
`1`. `consume-inputs!` entrega esa intención a `step`, que acelera si el jugador
está desbloqueado. Al soltar la tecla, la dirección es `0` y actúa la fricción;
con A y D juntas también se obtiene `0`.


Al presionar W, `handle-key-down!` marca `pending-jump` solo para una pulsación nueva; autorepeat y mantener W no crean pulsos adicionales. En un tick, `consume-inputs!` pasa `:jump? true` a `game/step` y limpia el pulso. `apply-jump` revisa el bloqueo de aparición, el apoyo y la recarga aérea. El mundo siguiente guarda `vy`, el contador y los cambios por contacto; el cliente lo dibuja. Si W se presiona durante la caída inicial, el pulso se consume mientras el jugador sigue bloqueado y no se reproduce después: hay que volver a pulsar W ya en tierra.

### Colección de plataformas

`:platforms` es un vector de mapas con `:id` estable y `:type`. `step` pasa
el vector a `supporting-platform`, `apply-jump` y `collide-platforms`. El
renderizador usa `doseq` para dibujar cada entrada. La escena contiene la plataforma principal sólida y tres semisólidas.
`case` elige la regla de contacto según `:type`; `filter` selecciona apoyos
y `sort-by` ordena por tiempo e ID para resolver empates de forma estable.
Las semisólidas aceptan solo contactos descendentes con su parte superior.

### Excepción temporal para bajar

El jugador guarda `:drop-through-platform-id`, inicialmente `nil`. Un S
aceptado devuelve un mapa con el ID de la semisólida de apoyo; el mapa de
plataformas permanece igual. `drop-through-platform?` permite ignorar solo
esa entrada. `clear-drop-through` devuelve el ID a `nil` al terminar el cruce,
volver por encima de la superficie o desaparecer esa plataforma.

`if-let` y `when-let` vinculan un valor cuando no es `nil` ni `false`.
`when-not` ejecuta su cuerpo cuando una condición es falsa. `earliest-contact`
usa `juxt` para ordenar primero por tiempo y luego por ID; así el resultado no
depende del orden del vector. Las transiciones siguen siendo funciones puras.

### Pulso de S

`pending-drop` es independiente de `pending-jump`: leer no los consume,
`consume-inputs!` limpia ambos y conserva las teclas mantenidas. Al pulsar S
sobre una semisólida, `step` crea la excepción y deja que la gravedad haga
bajar el cuerpo. Si también se pulsó W, la bajada tiene prioridad allí.
En el aire, S se descarta; W aún puede usar el salto aéreo disponible para
volver arriba y aterrizar. Blur, `stop!` e `init` borran las solicitudes.

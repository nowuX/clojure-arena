# Guía breve de Clojure y ClojureScript

La simulación compartida está en `src/clojure_arena/game.cljc`; los eventos y el dibujo del navegador están en `src/clojure_arena/client.cljs`. Esta guía cubre los conceptos que usa el juego. Consulta [Simulación](simulation.md) para las reglas.

## Clojure compartido: `game.cljc`

### Mapas y funciones

`def` nombra un valor y `defn` define una función. El mundo es un mapa; sus claves son keywords como `:players`, `:spawn-locked?` y `:air-jumps-left`:

```clojure
{:players {:player-1 {:x 400 :y 160 :vx 0 :vy 0 :radius 16
          :spawn-locked? true :air-jumps-left 1 :facing :right}}}
```

`step` recibe mundo, entradas por ID y `dt` en segundos; `step-player` recibe
un personaje, las plataformas, sus entradas y `dt`. El argumento `{:keys [horizontal jump? drop?] :or {horizontal 0 jump? false drop? false}}` extrae valores del mapa y fija sus valores por defecto. La simulación devuelve mapas nuevos; no consulta el navegador ni cambia el argumento original.

### Condiciones y valores nuevos

Las funciones con `?`, como `grounded?`, responden una pregunta. `supporting-platform` devuelve el mapa de plataforma si el jugador está apoyado arriba o `nil` si no lo está. `if`, `and` y `not` combinan condiciones; `cond` elige una rama según varios casos. `assoc` devuelve un mapa con un valor reemplazado y `update` calcula un valor nuevo para una clave.

`apply-jump` usa el estado del jugador, las plataformas y el pulso `jump?`. Con controles desbloqueados, acepta el salto terrestre si `grounded?` es verdadero y conserva la recarga aérea; en el aire acepta un solo salto si `:air-jumps-left` es positivo y reduce el contador. En otro caso deja el jugador sin lanzamiento. `step-player` aplica esta decisión antes de sumar gravedad.

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

## Clojure compartido: fases y resultados en `match.cljc`

`case` elige una transformación según `:phase`: esperar conserva el mundo,
la cuenta atrás reconstruye jugadores al llegar a cero, `:running` compone
`game/step` con reloj y resultado, y `:finished` cuenta el intermedio. No crea
otro reloj ni muta jugadores; devuelve mapas nuevos.

`contender?` consulta el roster y su marca `:forfeited?`. `select-keys` forma
el subconjunto que entra a física; `merge` devuelve sus resultados al mapa
que también conserva observadores. Con `reduce-kv` se copian vidas y porcentaje
al roster después del tick. Una conexión y un participante de partida son
registros relacionados por ID, con ciclos de vida distintos.

`ranked-standings` usa `sort-by` y `juxt` para ordenar por abandono, vidas
negadas, porcentaje e ID. El ID ordena la presentación; comparar vidas y
porcentaje con `==` detecta el empate real. `result-for` calcula un valor o
`nil`; `finish-if-decided` lo guarda una sola vez. Reconstruir durante la
revancha reutiliza la fábrica y conserva únicamente identidad y spawn.

## ClojureScript del navegador: `client.cljs`

### Átomos y eventos

`defonce` conserva referencias entre recargas de desarrollo; `atom` contiene una referencia mutable del cliente; `@world` la lee y `swap!` guarda el resultado de aplicar una función al valor actual. `reset!` reemplaza el contenido completo de un átomo. `pressed-keys` representa teclas mantenidas en un conjunto (`#{}`); `conj`, `disj` y `contains?` agregan, quitan y consultan sus elementos. `pending-jump`, `pending-drop` y `pending-attack` conservan pulsos independientes de W, S y clic hasta un tick. `read-inputs` lee el estado sin consumirlo; `consume-inputs!` lee y limpia los tres pulsos.

Un evento W o S nuevo y no repetido marca su pulso. Soltar la tecla quita su estado mantenido, pero no borra el pulso capturado. `handle-blur!` limpia las teclas y los tres pulsos; `stop!` también lo hace mediante esa función. Así, un toque rápido puede llegar al siguiente tick y un pulso no se repite en los ticks siguientes.

`js/window`, `.addEventListener`, `.-code` y `(.preventDefault event)` son interoperabilidad con JavaScript. `frame` usa `requestAnimationFrame`; `advance-world!` ejecuta ticks de 25 ms solo en modo local. Con admisión
aceptada, el servidor avanza y el cliente dibuja snapshots. Después, `draw-world` llama a las funciones que pintan el fondo, las plataformas y los jugadores; `render-players!` actualiza el panel. Dibujar no avanza la simulación. El estado visible de inicio es `Arena lista.`.

## Recorrido local de A/D y W

Al mantener D, el evento agrega `KeyD` al conjunto y `horizontal-input` produce
`1`. `consume-inputs!` entrega esa intención al cliente, que la coloca bajo
`:player-1` antes de llamar a `step`. `step-player` acelera si ese jugador
está desbloqueado. Al soltar la tecla, la dirección es `0` y actúa la fricción;
con A y D juntas también se obtiene `0`.


Al presionar W, `handle-key-down!` marca `pending-jump` solo para una pulsación nueva; autorepeat y mantener W no crean pulsos adicionales. En un tick, `consume-inputs!` entrega `:jump? true` y el cliente lo envía a `game/step` bajo `:player-1` y limpia el pulso. `apply-jump` revisa el bloqueo de aparición, el apoyo y la recarga aérea. El mundo siguiente guarda `vy`, el contador y los cambios por contacto; el cliente lo dibuja. Si W se presiona durante la caída inicial, el pulso se consume mientras el jugador sigue bloqueado y no se reproduce después: hay que volver a pulsar W ya en tierra.

### Colección de plataformas

`:platforms` es un vector de mapas con `:id` estable y `:type`. `step-player` pasa
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
sobre una semisólida, `step-player` crea la excepción y deja que la gravedad haga
bajar el cuerpo. Si también se pulsó W, la bajada tiene prioridad allí.
En el aire, S se descarta; W aún puede usar el salto aéreo disponible para
volver arriba y aterrizar. Blur, `stop!` e `init` borran las solicitudes.

### Jugadores por ID

`:players` es un mapa cuya clave identifica a cada personaje. `make-player`
construye sus datos; `get-in` consulta rutas como `[:players :player-1 :x]`
y `assoc-in` o `update-in` devuelven un mundo con esa ruta actualizada.
Cambiar el color no cambia la identidad. `vals` obtiene los personajes para
que `doseq` dibuje cada cuerpo sin modificar el mapa.

`reduce-kv` recorre las claves y valores de `:players` y construye otro mapa:
para cada ID llama a `step-player` con `(get inputs-by-player player-id)`.
Una entrada ausente o `nil` se interpreta como neutral. Como se recorre el
mapa de personajes existentes, una entrada con ID desconocido no crea cuerpos.
El estado de un personaje no depende del orden de los otros.

```clojure
(game/step world {:player-1 {:horizontal 1}
                  :player-2 {:horizontal -1 :jump? true}} 0.025)
```

En modo local, el navegador usa solo `{local-player-id (consume-inputs!)}`, con
`local-player-id` igual a `:player-1`. Los demás personajes siguen recibiendo
gravedad y colisiones aunque no tengan controles asignados.

### Dirección recordada

`:facing` vale `:left` o `:right` y empieza en `:right`. `cond` consulta la
intención horizontal ya filtrada por el bloqueo: `neg?` guarda izquierda,
`pos?` guarda derecha y `:else` conserva el mapa. Se decide antes de acelerar,
por eso el personaje puede mirar a la izquierda mientras aún se mueve a la
derecha. La fricción y las colisiones no escriben ese campo.

En `draw-player`, `if` elige un desplazamiento de medio radio según `:facing`.
La interoperabilidad `.arc` dibuja primero el cuerpo y luego un punto blanco.
Soltar teclas o perder foco limpia entradas, pero no borra esta memoria.

### Pulso del clic principal

`pending-attack` es otro átomo booleano. El canvas registra `pointerdown` y
acepta solo `button = 0`; el evento indica intención, no un impacto. Varios
clics antes del tick conservan un único `true`. `consume-inputs!` lo entrega
como `:attack?` y lo limpia con W/S. `input-canvas` recuerda dónde quitar el
listener durante `stop!`, incluso si el DOM reemplazó el canvas.

### Aceptar un golpe

`step-melee` transforma un personaje: resta `dt` al cooldown y usa `max` y una
tolerancia pequeña para llegar a cero. Si `:attack?` es verdadero, no está
bloqueado y el cooldown terminó, `update` incrementa `:melee-strike-id` y
`assoc` guarda 0,25 segundos. Aceptar un intento aún no significa acertar.

### Consultar objetivos sin efectos

`melee-target-ids` usa `reduce-kv` para construir un conjunto (`#{}`) de IDs.
`case` elige el signo de X según la mirada y la distancia al cuadrado evita
una raíz. `step` termina primero el movimiento de todos; luego consulta el
mismo mapa para cada secuencia nueva. Leer ese mapa no modifica posiciones.

### Acumular impactos y dibujar valores

`reduce` agrupa los IDs seleccionados con las miradas de sus atacantes; el
tamaño del grupo determina los diez puntos por impacto que se suman a
`:accumulated-percentage`. El porcentaje no es vida ni velocidad.
El cliente lee `@world` una vez por frame y entrega el mismo valor al canvas y
al panel. `createElement`, `textContent` y `appendChild` crean las filas del DOM;
los efectos siguen fuera de las funciones puras de simulación.

### Resolver exactamente una vez

El ID de secuencia aceptado se compara con `:melee-resolved-strike-id` del
mismo atacante. `filter` conserva solo los pendientes y `reduce` guarda sus
marcadores junto con los efectos en un mapa nuevo. Un fallo también se consume.
Reutilizar el mapa devuelto no vuelve a sumar porcentaje: es idempotencia.

### Una función con dos aridades

`melee-knockback-impulse` acepta dos argumentos para usar la configuración
normal, o tres para recibir un mapa de configuración explícito. `frequencies`
cuenta miradas y devuelve la suma por eje. Recibe el porcentaje final de todos
los impactos del tick; el resultado es un cambio de velocidad, no una posición.

### Separar velocidad voluntaria y recibida

El mapa guarda velocidad total y componentes recibidas. `step-player` resta
la X recibida anterior para obtener la voluntaria, transforma ambas y vuelve
a sumarlas. Un golpe actualiza las dos representaciones con `update`; no mueve
la posición otra vez. `resolve-contact` proyecta los dos vectores sobre la
misma normal. Un salto válido reemplaza Y y borra solo su componente recibida.

### Recorrido local completo

El cliente consume las acciones, conserva la dirección y las coloca bajo su ID.
`step` avanza todos los personajes y consulta objetivos sobre ese resultado
común. `apply-melee-hits` devuelve porcentaje, impulso y secuencias resueltas
juntos. El átomo del cliente guarda el mundo nuevo; dibujo y panel leen el
mismo valor. Las funciones compartidas no leen átomos ni el reloj del navegador.

## Clojure del servidor JVM

`deps.edn` declara dependencias con versiones fijas y aliases de ejecución.
`clojure -M:server` llama a `-main`; `just server` evita repetir el comando.
`parse-options` usa `parse-long` y `ex-info` para validar argumentos. Ring
representa solicitudes y respuestas con mapas; los middlewares componen el
handler. `start!` y `stop!` son la frontera con efectos de http-kit: sirven
archivos y administran un socket, sin modificar la simulación pura.

### EDN y transporte del navegador

EDN representa datos de Clojure sin ejecutar código. `decode` lee un valor y
comprueba el fin del texto; `try`/`catch` distingue los errores del protocolo.
`server-message?` valida mapas exactos antes de aceptarlos. En ClojureScript,
`js/URL` obtiene el endpoint y `js/WebSocket` administra eventos. `identical?`
comprueba que un callback todavía pertenezca al socket actual.

### Agente de sala y efectos capturados

`agent` guarda el envelope de sala. `send` encola una función y serializa sus
transiciones; el núcleo puro devuelve `{:room ... :effects ...}`. `add-watch`
recibe ese valor después de guardarlo y entrega las respuestas capturadas.
El token es un objeto privado de la conexión; la identidad visible es otro dato.
Así la capacidad y la elección de color forman una sola decisión de estado.

### Configuración de aparición

`spawn-config` es un mapa de color lógico a relleno y coordenadas. La sala
reutiliza `make-player`; no tiene otra versión de la física. Una misma cadena
`->` inserta miembro, personaje y entrada neutral con `assoc-in`. Se devuelve
todo junto, sin exponer tokens privados dentro del mundo serializable.

### Intenciones por conexión

`merge-pending-input` construye un mapa nuevo: toma la última dirección y usa
`or` para conservar acciones breves entre ticks. El servidor agrega su token
privado al mensaje interno; el navegador no puede elegir el jugador. Los
handlers de teclado envían datos inmediatamente, mientras RAF sigue dedicado
al dibujo; el avance local solo ocurre sin una conexión admitida.

### Un reloj y una cola

Un `agent` serializa admisiones, entradas y ticks mediante `send`. La función
pura devuelve `{:room ... :effects ...}` y el watch entrega los efectos ya
confirmados. El planificador de 25 ms admite como máximo un tick pendiente;
no acumula una ráfaga para recuperar retrasos. `compare-and-set!` protege esa
compuerta, mientras `game/step` sigue trabajando solo con valores.

### Publicar valores confirmados

Un snapshot captura el mundo y sus destinatarios en una sola transición. El
watch pasa ese valor a una cola acotada (`ArrayBlockingQueue`); un worker lo
codifica una vez. No vuelve a leer la sala para reconstruir el mensaje.
ClojureScript valida claves, tipos y números finitos antes de reemplazar el
mundo visible. Un átomo separado conserva el mundo offline para desconexiones.

### Estados terminales de transporte

La admisión `:full` tiene prioridad al presentar el estado y al filtrar los
callbacks. Cerrar un socket rechazado no convierte el rechazo en un cierre
genérico. `start!` llama a `stop!` para descartar datos de la sesión anterior.

### Limpieza atómica y reloj monotónico

`update` con `dissoc` construye nuevas tablas sin el miembro, su entrada y su
cuerpo dentro de una transición de sala. La compuerta de retiro evita repetir
efectos entre cierre y timeout. `System/nanoTime` mide la vida de la conexión;
ese tiempo no entra en `game/step`. Ping/Pong de WebSocket comprueba el desafío
exacto y funciona aparte de los mensajes EDN de diagnóstico.

### Identidad de una sesión

`identical?` comprueba que el callback pertenece al mismo objeto WebSocket
actual. Desactivar handlers y vaciar los átomos al reemplazarlo impide que un
evento tardío restaure datos antiguos. Cookies y colores no identifican una
sesión: cada conexión aceptada recibe un nuevo ID monotónico.

### Límites como datos

`:blast-bounds` guarda límites independientes del canvas. `outside-arena?`
recibe personaje y límites sin leer ni cambiar el mundo; compara el extremo
completo del círculo con cada borde mediante desigualdades estrictas. El
validador del snapshot exige cuatro números finitos y límites no invertidos.

### Vidas y ciclo del jugador

`make-player` devuelve `:stocks 3` y `:lifecycle :active`. Las keywords separan
estados sin objetos mutables: activo, esperando reaparición o eliminado. El
protocolo valida juntos estado y número entero de vidas; el porcentaje se
conserva como otro campo, no representa salud.

### Una salida consume una vida

`active-player?` filtra quién participa en física y combate. Después de mover,
`resolve-ring-out` devuelve un estado inactivo con una vida menos; los ticks
posteriores no vuelven a cobrar la misma caída. El mapa conserva su clave e
identidad. `filter` omite el cuerpo al dibujar sin eliminarlo de la conexión.

### Estado visible sin mutar el juego

`player-state-label` convierte una keyword en texto español. El panel recorre
todos los registros, incluyendo los inactivos, mientras Canvas filtra solo los
activos. `textContent` muestra porcentaje y oportunidades; ningún renderizador
cambia las vidas ni el ciclo del jugador.

### Tiempo de reaparición

`:respawn-seconds-left` es otro dato inmutable. Solo los ticks posteriores a la
pérdida restan `dt`; `remaining-seconds` elimina residuos numéricos pequeños
para alcanzar cero exactamente. El navegador usa `js/Math.ceil` para mostrar
segundos redondeados hacia arriba sin controlar la espera.

### Reconstruir una vida

`step-respawn` llama de nuevo a `make-player` con `:spawn-x`, `:spawn-y` y el
color guardados. Luego restaura solo las vidas restantes; la clave externa del
mapa mantiene el ID. Así reinicia porcentaje y estado físico/de combate sin
una segunda lista de valores iniciales. La rama de retorno no ejecuta física
ni interpreta acciones; el siguiente tick inicia la caída con controles bloqueados.


### Protección al reaparecer

`grant-spawn-protection` devuelve otro mapa con dos segundos solo si el jugador
está activo. El tick reduce ese dato antes de buscar objetivos; la consulta y
el resolver excluyen destinos protegidos. Los marcadores de golpe se confirman
igual, evitando impactos aplazados. Canvas dibuja un anillo blanco y el panel
muestra segundos redondeados, sin controlar el reloj de la simulación.

## ClojureScript: presentar la partida autoritativa

`render-match!` recibe el mismo snapshot usado por Canvas. `format-clock`
combina `js/Math.ceil`, `js/Math.floor` y `.padStart` para mostrar `mm:ss`;
no resta tiempo. El ganador y la clasificación salen de `:result`, por lo que
se pueden mostrar aunque su conexión ya no exista. `panel-state-label`
distingue observadores de contendientes eliminados sin adivinar un ganador.
`textContent` escribe texto y los colores se limitan a las muestras de identidad.

### Diagnósticos del transporte

`case` traduce categorías conocidas a mensajes; `cond->` conserva metadatos
opcionales de cierre. Los datos de diagnóstico viven en un mapa independiente
de la condición de autoridad: un socket abierto y admitido sigue usando el
reloj del servidor aunque un mensaje recibido sea inválido. Los callbacks
comparan la identidad del socket para ignorar sesiones anteriores.

### Cachés de presentación

Un mapa asocia cada ID exacto con sus nodos DOM. `identical?` comprueba si el
contenedor sigue siendo el mismo objeto; la igualdad de valores evita escribir
texto sin cambios. Guardar el color original evita confundir un hexadecimal
con el formato `rgb(...)` que devuelve CSS. Estas cachés se limpian al detener
el cliente y no forman parte del mundo inmutable de la simulación.

### Identidad visible y estado de Canvas

La identidad controlada se calcula a partir de la conexión y del primer snapshot;
no se adivina por el color ni se guarda dentro del mundo. `try`/`finally` restaura
las propiedades temporales de Canvas incluso si dibujar falla. `aria-current`
expone la selección en el DOM; CSS añade el marcador neutral sin alterar el ID.
El ancho CSS escala la presentación, no las coordenadas de simulación.

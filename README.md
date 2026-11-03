# metalmind-android

App de Android para **capturar notas rápido** en un vault de [Metalmind](https://github.com/alex-glez-felix/metalmind) y **consultarlas** sin abrir la laptop.

Se abre directo en el campo de texto con el teclado arriba. Escribes, guardas, y queda lista para la siguiente. La lupa busca en el vault y abre las notas para leerlas; editarlas o reorganizarlas se hace en el escritorio.

No sincroniza nada por su cuenta: escribe cada captura como un archivo `.md` **nuevo** en una carpeta del vault, y la sincronización la hace la herramienta que ya uses (Syncthing, git). Como nunca modifica un archivo que ya existe, no hay conflictos. Sin tags, la nota cae al buzón.

Sin cuentas, sin servidores, sin red, sin analítica: la carpeta se abre con el selector del sistema. El único permiso es el micrófono, para las notas de voz, y se pide al tocarlo.

## Cómo se guardan las notas

- **Nombre**: igual que `metalmind add`. La primera línea con letras o números, en minúsculas y con las palabras unidas por `-`, con acentos y otros alfabetos tal cual, hasta 50 caracteres sin cortar palabras: `Reunión del lunes` → `reunión-del-lunes.md`. Todo lo que no es letra o número separa palabras, así que el nombre nunca lleva `/ \ : * ? " < > |`.
- **Sin letras ni números** (solo emoji o puntuación): la fecha y la hora, `2026-10-18-090507.md`.
- **Si el nombre ya existe**: `idea-2.md`, `idea-3.md`, … Nunca se pisa una nota. Los nombres se comparan sin distinguir mayúsculas.
- **Contenido**: el texto tal cual, markdown plano, sin frontmatter. Los saltos de línea del final se juntan en uno.

## Uso

1. La primera vez, toca **Elegir carpeta del vault** y elige la carpeta donde quieres las capturas (la raíz del vault u otra dentro de él). El permiso queda guardado. Las notas de voz dejan su audio en `buzon/audio/` dentro de esa carpeta.
2. Escribe y guarda con el botón o con la tecla de acción del teclado (con teclado físico, `Ctrl+Enter`). La tecla de acción guarda en vez de saltar de línea: para notas de varias líneas, pega el texto.
3. Para cambiar de carpeta, **Cambiar carpeta** arriba a la derecha.

### Tags

Sin tags, la nota cae al buzón. Para ponerle uno, escribe `#`: sobre el teclado aparecen los tags del vault, de los que están en más notas a los que menos, filtrados por lo que vas escribiendo (`#ru` → `#Rust`). Tocar uno escribe su forma más usada en el vault, así no nacen variantes (`#rust` y `#Rust` son el mismo tag).

Los tags salen de los `.md` de la carpeta elegida y sus subcarpetas, con las reglas de Metalmind: `#tag` al inicio de línea o tras un espacio (fuera de código y comentarios) y `tags:` del frontmatter. Para que estén todos, elige la raíz del vault. La primera vez se leen todas las notas; después la app guarda lo leído en su caché (nunca en el vault) y solo relee las que cambiaron.

### Links

Para enlazar otra nota, escribe `[[`: sobre el teclado aparecen las notas del vault, las que cambiaron hace menos primero, filtradas por lo que vas escribiendo sin distinguir mayúsculas ni acentos (`[[reu` → *Reunión del lunes*). Primero las que empiezan así, después las que lo tienen en el nombre y al final en la carpeta (`[[proy` → las de `proyectos/`). Si dos notas se llaman igual, debajo del nombre sale su carpeta.

Tocar una escribe `[[nombre]]` como lo escribe Metalmind: solo el nombre si basta para llegar a esa nota, y si no, con las carpetas que hagan falta (`[[proyectos/rust]]`). Se cuenta desde la carpeta elegida, así que para que los nombres sean los del vault, elige la raíz. Las notas cuyo nombre lleva `[`, `]`, `|` o `#` no salen: un `[[link]]` no puede nombrarlas.

### Formato

Sobre el teclado hay una barra con negrita, cursiva, lista, checkbox y código. Escribe markdown plano (`**negrita**`, `*cursiva*`, `- `, `- [ ] `, `` `código` ``), así que la nota queda igual en el escritorio:

- **Negrita, cursiva y código** envuelven lo seleccionado; con varias líneas, el código va en un bloque ```` ``` ````. Si lo seleccionado ya tiene ese formato (o una parte), lo quitan. Sin selección escriben el par de marcas con el cursor en medio, y al tocar otra vez el cursor sale del par para seguir escribiendo sin formato.
- **Lista y checkbox** se ponen al inicio del renglón (o de todos los seleccionados), y tocar otra vez los quita. Una lista pasa a checkbox y al revés. Con el cursor al final de un elemento, empiezan otro debajo (la tecla de acción del teclado guarda, y así se escribe una lista en el teléfono); tocar otra vez en ese elemento vacío lo borra. Para quitarle la marca a un elemento con texto, pon el cursor dentro del renglón.
- Con teclado físico, `Enter` tras un elemento empieza otro igual, y `Enter` en uno vacío termina la lista.

| Atajo | Formato |
| --- | --- |
| `Ctrl+B` | Negrita |
| `Ctrl+I` | Cursiva |
| `Ctrl+E` | Código |
| `Ctrl+Shift+8` | Lista |
| `Ctrl+Shift+9` | Checkbox |

### Buscar

Toca la lupa arriba a la derecha (con teclado físico, `Ctrl+K`). Los resultados salen conforme escribes:

- Cada palabra tiene que estar en el título (el nombre del archivo) o en el texto de la nota, en cualquier orden y sin distinguir mayúsculas ni acentos: `reunion lunes` encuentra *Reunión del lunes*. El frontmatter no se busca.
- `#tag` deja solo las notas con ese tag, con las mismas reglas que las sugerencias (`#rust` es `#Rust`, en el texto o en `tags:`). Al escribir `#` aparecen los tags del vault, como en la captura. Se combina con palabras: `#rust async`.
- Primero las notas con lo buscado en el título; después, las más recientes. Sin nada escrito, las últimas que cambiaron.
- `↓` y `↑` marcan un resultado y `Enter` lo abre (sin marcar, el primero); `Esc` vuelve.

Se busca en lo que la app ya tiene en su caché (la misma de los tags, que ahora guarda también el texto), así que escribir no relee el vault: al abrir la búsqueda solo se releen las notas que cambiaron.

Tocar un resultado abre la nota **solo para leer**, con el markdown renderizado como en la vista de lectura de Obsidian: encabezados, **negrita**, *cursiva*, ~~tachado~~, `==resaltado==`, código, listas (también de tareas, `☐`/`☑`), citas y callouts, y líneas horizontales; los comentarios `%% %%` no se ven. Las tablas, fórmulas y HTML se muestran tal cual. Cada línea de la nota es una línea en pantalla, como se escribió.

- Un `#tag` busca las notas con ese tag.
- Un `[[link]]` (o `[texto](otra-nota.md)`) abre la nota a la que apunta, por nombre o ruta, sin distinguir mayúsculas. Con alias (`[[nota|texto]]`) se ve el alias.
- Los links a la web (`[texto](https://…)`, `<https://…>` o una URL suelta) se abren en el navegador.
- El texto se puede seleccionar y copiar (sin las marcas de markdown).

Atrás, o `Esc`, vuelve a la nota anterior y luego a la búsqueda; `Ctrl+K` vuelve directo a la búsqueda.

### Tablet y pantallas grandes

Cuando la ventana es grande en las dos direcciones (al menos 600 × 480 dp: una tablet en cualquier orientación, o una ventana dividida que quede así de grande), la app se parte en dos paneles: la búsqueda a la izquierda y, a la derecha, la nota abierta o, si no hay ninguna, la captura. Un teléfono, también acostado, sigue con un panel. La app no fija la orientación: al girar se reacomoda sin perder lo escrito ni la nota abierta. Con dos paneles, la lista se relee al volver a la app y al guardar, así la nota recién guardada ya sale.

Con teclado físico, en la captura con dos paneles (en un panel, `Ctrl+K`, `Enter`, `Esc` y `Ctrl+Enter` hacen lo de siempre):

| Atajo | Qué hace |
| --- | --- |
| `Ctrl+K` | Ir al campo de búsqueda |
| `↓` / `↑` | En la búsqueda, recorrer los resultados; con dos paneles, cada uno se ve a la derecha |
| `Enter` | Abrir el resultado marcado (o el primero) |
| `Esc` | Cerrar la nota (o volver a la anterior); sin nota, volver a la captura |
| `Ctrl+N` | Nota nueva: cierra la nota abierta y va a la captura |
| `Ctrl+Enter` | Guardar la captura |

### Notas de voz

El micrófono de arriba **graba siempre a un audio en el vault**, sin red y sin modelo: al detener, el audio queda en `buzon/audio/` y la app avisa dónde (*Audio guardado en buzon/audio/2026-11-03-091500.m4a*). El audio es la fuente de verdad; el texto se saca de él en el teléfono, con Whisper, o después, fuera del teléfono (ver [Audios pendientes](#audios-pendientes)).

- **Grabar**: pausa, sigue y *Guardar audio* (con Whisper, *Transcribir*). Hasta 10 minutos. La pantalla queda encendida mientras graba; salir de la app corta la grabación sin guardarla.
- **Descartar** tira la grabación: no queda ningún audio. Es la única forma de que una grabación no llegue al buzón.
- **Dónde**: `buzon/audio/` **dentro de la carpeta elegida**, igual que las notas. Si elegiste la raíz del vault, es `<vault>/buzon/audio/`; si elegiste una subcarpeta (por ejemplo `capturas/`), es `capturas/buzon/audio/`. La app crea las carpetas si no están.
- **Formato y nombre**: m4a (AAC, unos 240 KB por minuto), con la fecha y la hora de cuando se detuvo: `2026-11-03-091500.m4a`. Si el nombre ya existe, `-2`, `-3`, …: nunca se pisa un archivo.
- **Si el vault no responde** (se perdió el permiso de la carpeta, o la carpeta ya no está), el audio se queda en la app, se avisa, y pasa al buzón la próxima vez que se abre la captura con una carpeta que funcione.
- **Atajo**: mantén pulsado el ícono de la app → *Nota de voz* abre la captura grabando.

#### Transcribir en el teléfono (Whisper)

Opcional y **apagado de entrada**. Mientras grabas, toca *Transcribir en el teléfono: no* para abrir los ajustes de voz y enciende *Transcribir en el teléfono con Whisper*. Con Whisper encendido, al detener la voz pasa a texto **en el teléfono, sin red** y sin Google Play Services, con [whisper.cpp](https://github.com/ggml-org/whisper.cpp). El texto queda en el campo, donde está el cursor, con el enlace al audio debajo (`![[2026-11-03-091500.m4a]]`), para revisarlo antes de guardar; el nombre de la nota sale de la primera línea, como en cualquier captura.

- **Sin modelo**: con Whisper encendido, el micrófono pide primero el modelo (abajo). Ahí mismo se puede apagar Whisper y *Grabar* solo el audio.
- **Si falla la transcripción** (o no se oyó nada, o la cortas con *No transcribir*), el audio ya está en el buzón y la app lo dice.
- **Idioma**: español, con las palabras en inglés tal como se dicen ("revisar el pull request").
- **Cuánto tarda**: mientras transcribe se ve el tiempo que lleva y cuánto audio era; al terminar, un aviso dice cuánto tardó (*Transcrito en 0:08 (audio de 0:30)*). Si `small` tarda más que el audio, prueba `base`.
- **Si actualizas** desde una versión que ya transcribía y tenías un modelo importado, Whisper sigue encendido: nada cambia sin avisar. En una instalación nueva viene apagado.
- **No borres el enlace** al audio de la nota si quieres que cuente como transcrito: sin él, el audio vuelve a quedar pendiente.

**El modelo** se baja una vez. La app no tiene permiso de red: *Descargar* lo abre en el navegador y *Elegir archivo* lo copia a la app (nunca al vault) y comprueba su SHA-256; después puedes borrar la descarga. Un archivo incompleto o dañado se rechaza.

| Modelo | Tamaño | Español (WER en FLEURS) | Para |
|---|---|---|---|
| `small` (q5_1) | 190 MB | 5.6 % | Recomendado |
| `base` (q5_1) | 60 MB | 9.9 % | Teléfonos más lentos, o si `small` tarda demasiado |
| `large-v3-turbo` (q5_0) | 574 MB | — (large-v2: 3.0 %) | Quien no tenga prisa |

El WER es el del artículo de Whisper para el modelo sin cuantizar (tabla D.2.4); `q5` ocupa un tercio y pierde poco. También sirve cualquier otro modelo `ggml-*.bin` de whisper.cpp: se usa tal cual, sin comprobar.

### Audios pendientes

Los audios del buzón que nadie transcribió quedan para un script fuera del teléfono (por ejemplo en el servidor, al que Syncthing lleva el vault). La app no trae ese script; esto es lo que tiene que saber para **no transcribir nunca dos veces**:

- **Dónde**: los `*.m4a` de cada carpeta `buzon/audio/` del vault (una por cada carpeta que se haya elegido en la app; con la raíz, `<vault>/buzon/audio/`). No cuentan los ocultos: Syncthing escribe `.syncthing.<nombre>.tmp` mientras copia.
- **Transcrito = alguna nota lo nombra.** Un audio `2026-11-03-091500.m4a` ya está transcrito si algún `.md` del vault contiene su nombre de archivo entero, sin distinguir mayúsculas: el enlace que escribe la app (`![[2026-11-03-091500.m4a]]`), o cualquier otro (`[[…|alias]]`, `[…](buzon/audio/…)`). "Entero" quiere decir sin letra, número, `.`, `_` ni `-` pegados delante, ni letra, número, `_` o `-` detrás: `…-091500.m4a` no cuenta como `…-091500-2.m4a`. Si ninguna nota lo nombra, está **pendiente**.
- **Lo que hace el script** con un pendiente: transcribirlo y crear una nota que lo enlace (`![[2026-11-03-091500.m4a]]`), por ejemplo con `metalmind add`. Esa nota es justo la marca de transcrito, así que la próxima pasada se lo salta. El audio no se toca ni se mueve: es la fuente de verdad, y el texto se puede regenerar.
- **Esperar un poco**: con Whisper en el teléfono, el audio llega al vault al detener y la nota que lo enlaza llega después, cuando se guarda la captura (segundos o minutos; Syncthing los puede traer en cualquier orden). Conviene procesar solo los audios sin cambios desde hace un rato (por ejemplo, `mtime` de más de 30 minutos) y volver a mirar que siga pendiente justo antes de crear la nota.
- **No buscar en carpetas ocultas** del vault: `.stversions/` (las versiones viejas de Syncthing) puede tener una nota borrada que lo nombraba, y `.git/`, `.metalmind/` tampoco son notas.

En shell, para un audio:

```sh
name=2026-11-03-091500.m4a
pattern="(^|[^[:alnum:]._-])${name//./\\.}([^[:alnum:]_-]|\$)"
grep -rqiE --include='*.md' --exclude-dir='.*' -- "$pattern" "$VAULT" && echo transcrito || echo pendiente
```

La regla vive en `core` (`AudioInbox.isTranscribed`, con tests): si cambia, cambia ahí y aquí.

**Por qué una nota que lo enlaza y no un `.md` hermano** (`buzon/audio/2026-11-03-091500.md`): en Metalmind todo `.md` es una nota, y una sin tags es del buzón. Un `.md` por audio llenaría el buzón de notas de relleno o con el mismo texto que la nota de verdad. Además, la nota que enlaza el audio es lo que de todas formas se crea (la captura en el teléfono, `metalmind add` en el script): la marca no es un archivo aparte que se pueda desincronizar, perder o borrar sin la nota, y si alguien borra el enlace a propósito, el audio vuelve a estar pendiente, que es lo correcto porque ninguna nota guarda su texto.

### Entrar más rápido

- **Compartir con Metalmind**: desde cualquier app, *Compartir* → *Compartir con Metalmind* abre la captura con el texto o el link ya puestos. Con un link de un navegador, el título de la página va en la primera línea (y da el nombre del archivo) y la URL debajo. Al guardar, vuelves a la app de donde compartiste.
- **Tile de Ajustes rápidos**: edita los Ajustes rápidos (el lápiz del panel de notificaciones) y arrastra *Nota rápida*. Con el teléfono bloqueado, primero pide desbloquear.
- **Widget**: mantén pulsada la pantalla de inicio → *Widgets* → *Metalmind* → *Nota rápida*. Es una barra que abre la captura.
- **Nota de voz**: mantén pulsado el ícono de la app → *Nota de voz*.

## Instalar el APK

Todavía no hay versiones publicadas. Cada build de CI deja el APK debug como artefacto:

1. En la pestaña **Actions** del repo, abre la última ejecución verde de `android` en `main`.
2. Descarga el artefacto `metalmind-debug-apk` (un `.zip` con `app-debug.apk`) y descomprímelo.
3. Pásalo al teléfono y ábrelo. Android pedirá permitir instalar apps de esa fuente (el navegador o el gestor de archivos): acéptalo solo para esta instalación.

Todos los APK debug se firman con la misma clave (`app/debug.keystore`, que es pública: cualquiera podría firmar con ella un APK que se instale encima, así que instala solo los de este repo), así que uno nuevo se instala encima del anterior sin perder el modelo ni la carpeta. Los APK de CI anteriores a esa clave estaban firmados con otra: para pasar de uno de esos a uno nuevo hay que desinstalar una vez.

También por USB, con la depuración activada: `adb install app-debug.apk`.

Para compilarlo tú (JDK 17 y Android SDK con `platforms;android-35`):

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug test lint   # lo mismo que corre CI
```

## Sincronizar con Syncthing

La app solo escribe en una carpeta del teléfono; Syncthing la lleva al resto de tus equipos.

1. Instala Syncthing en el teléfono (por ejemplo Syncthing-Fork, desde F-Droid) y en la computadora donde está el vault.
2. Empareja los dos dispositivos (en cada uno, **Agregar dispositivo** con el ID del otro).
3. En la computadora, comparte la carpeta del vault con el teléfono. En el teléfono, acéptala y elige dónde guardarla, por ejemplo `Documentos/vault`. Tipo de carpeta: **Enviar y recibir**.
4. En Metalmind para Android, **Elegir carpeta del vault** y elige esa misma carpeta (`Documentos/vault`) o una dentro de ella.
5. Lo que captures aparece en la computadora cuando Syncthing sincroniza. Como cada captura es un archivo nuevo, no hay conflictos aunque edites el vault en la computadora al mismo tiempo.

Si no quieres el vault entero en el teléfono, comparte solo una subcarpeta (por ejemplo `capturas/`) y elígela en la app; Metalmind la ve como cualquier carpeta del vault.

Con git funciona igual: elige la carpeta del clon (por ejemplo, la de Termux o la de una app de git) y haz commit y push desde ahí.

## Desarrollo

- `core/`: Kotlin puro, sin Android. Nombres de archivo, contenido y el "crear sin pisar", los tags, los links (`LinkCompletion`), el formato (`Formatting`), el índice del vault (`VaultIndex`), la búsqueda (`NoteSearch`), el markdown de la vista de nota (`NoteMarkdown`) y las notas de voz (`VoiceSession`, `VoiceRecorder`, `Transcript`, el buzón de audio y su contrato en `AudioInbox`, el ajuste `WhisperSetting`, la lista de modelos en `VoiceModels`). Tests JVM: `./gradlew :core:test`.
- `app/`: la app, Jetpack Compose. Lee y escribe en la carpeta con el Storage Access Framework. Cuándo hay dos paneles y cómo se mueven las flechas por la lista están en `Panes`, con tests JVM: `./gradlew :app:testDebugUnitTest`.

- `app/src/main/cpp/`: el puente JNI con whisper.cpp. CMake baja la versión fijada (con su SHA-256) y compila ggml con una variante por cada familia de CPU ARM (dotprod, fp16, i8mm, SVE…); al arrancar se carga la mejor que tenga el teléfono. El código nativo se compila en Release (`-O3`) también en el APK debug: sin optimizar, whisper va unas 30 veces más lento (en el emulador, 199 s contra 7 s para 24 s de audio). CI lo comprueba. Whisper usa un hilo por núcleo sin contar los pequeños (`TranscribeThreads`), nunca más que los núcleos disponibles: con un hilo de más, cada paso espera al que no tiene núcleo y tarda decenas de veces más. Hace falta el NDK (`ndk;27.3.13750724`) y CMake 3.31.6 del SDK; Gradle los instala si faltan y aceptaste las licencias. Solo `arm64-v8a` (teléfonos) y `x86_64` (emulador).

### Probar la transcripción en el dispositivo

CI no corre whisper (necesita un modelo de cientos de MB). En un emulador o un teléfono por USB:

```sh
D=/sdcard/Android/data/io.github.alexglezfelix.metalmind/files
adb push ggml-small-q5_1.bin $D/modelo.bin
adb push voz.wav $D/voz.wav   # 16 kHz, mono, 16 bits, con el texto de WhisperDeviceTest
./gradlew connectedDebugAndroidTest
adb logcat -s WhisperDeviceTest Whisper whisper   # el texto, el tiempo, cuántas palabras acertó y la variante de CPU
```

En logcat, `Whisper` dice cuántos hilos usa, si ggml está optimizado y qué instrucciones tiene la variante elegida (`DOTPROD`, `MATMUL_INT8` = i8mm, `SVE`…); `whisper` dice de qué archivo la cargó (`load_backend: loaded CPU backend from …/libggml-cpu-android_armv8.6_1.so`). Para comparar hilos: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.threads=6`.

Sin esos archivos, `WhisperDeviceTest` se salta. `VoiceInboxDeviceTest` comprueba el m4a y, si la app ya tiene carpeta elegida, que el audio llegue a `buzon/audio/` sin pisar otro y que, sin carpeta, espere en la app y pase después. El micrófono del emulador no recibe audio del anfitrión con PipeWire, por eso la prueba usa un WAV en lugar del micrófono; lo demás (VoiceSession, JNI, whisper) es lo mismo que en la app.

Licencia: GPL-3.0. whisper.cpp y ggml son MIT; los modelos de Whisper, MIT (OpenAI).

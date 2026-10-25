# metalmind-android

App de Android para **capturar notas rápido** en un vault de [Metalmind](https://github.com/alex-glez-felix/metalmind).

Se abre directo en el campo de texto con el teclado arriba. Escribes, guardas, y queda lista para la siguiente.

No sincroniza nada por su cuenta: escribe cada captura como un archivo `.md` **nuevo** en una carpeta del vault, y la sincronización la hace la herramienta que ya uses (Syncthing, git). Como nunca modifica un archivo que ya existe, no hay conflictos. Sin tags, la nota cae al buzón.

Sin cuentas, sin servidores, sin red, sin analítica, sin permisos: la carpeta se abre con el selector del sistema.

## Cómo se guardan las notas

- **Nombre**: igual que `metalmind add`. La primera línea con letras o números, en minúsculas y con las palabras unidas por `-`, con acentos y otros alfabetos tal cual, hasta 50 caracteres sin cortar palabras: `Reunión del lunes` → `reunión-del-lunes.md`. Todo lo que no es letra o número separa palabras, así que el nombre nunca lleva `/ \ : * ? " < > |`.
- **Sin letras ni números** (solo emoji o puntuación): la fecha y la hora, `2026-10-18-090507.md`.
- **Si el nombre ya existe**: `idea-2.md`, `idea-3.md`, … Nunca se pisa una nota. Los nombres se comparan sin distinguir mayúsculas.
- **Contenido**: el texto tal cual, markdown plano, sin frontmatter. Los saltos de línea del final se juntan en uno.

## Uso

1. La primera vez, toca **Elegir carpeta del vault** y elige la carpeta donde quieres las capturas (la raíz del vault u otra dentro de él). El permiso queda guardado.
2. Escribe y guarda con el botón o con la tecla de acción del teclado (con teclado físico, `Ctrl+Enter`). La tecla de acción guarda en vez de saltar de línea: para notas de varias líneas, pega el texto.
3. Para cambiar de carpeta, **Cambiar carpeta** arriba a la derecha.

### Entrar más rápido

- **Compartir con Metalmind**: desde cualquier app, *Compartir* → *Compartir con Metalmind* abre la captura con el texto o el link ya puestos. Con un link de un navegador, el título de la página va en la primera línea (y da el nombre del archivo) y la URL debajo. Al guardar, vuelves a la app de donde compartiste.
- **Tile de Ajustes rápidos**: edita los Ajustes rápidos (el lápiz del panel de notificaciones) y arrastra *Nota rápida*. Con el teléfono bloqueado, primero pide desbloquear.
- **Widget**: mantén pulsada la pantalla de inicio → *Widgets* → *Metalmind*. Es una barra que abre la captura.

## Instalar el APK

Todavía no hay versiones publicadas. Cada build de CI deja el APK debug como artefacto:

1. En la pestaña **Actions** del repo, abre la última ejecución verde de `android` en `main`.
2. Descarga el artefacto `metalmind-debug-apk` (un `.zip` con `app-debug.apk`) y descomprímelo.
3. Pásalo al teléfono y ábrelo. Android pedirá permitir instalar apps de esa fuente (el navegador o el gestor de archivos): acéptalo solo para esta instalación.

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

- `core/`: Kotlin puro, sin Android. Nombres de archivo, contenido y el "crear sin pisar". Tests JVM: `./gradlew :core:test`.
- `app/`: la app, Jetpack Compose. Escribe en la carpeta con el Storage Access Framework.

Licencia: GPL-3.0.

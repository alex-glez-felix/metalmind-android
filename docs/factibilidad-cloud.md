# Factibilidad: compilar Android en el entorno cloud

Fecha: 2026-10-24

## Resultado

**No es factible con la política de red actual.** El entorno no puede descargar el
Android SDK ni el Android Gradle Plugin: `dl.google.com` está bloqueado por el
proxy de salida (respuesta 403 al `CONNECT`, denegación de política). Según las
instrucciones de la prueba, me detuve en el paso 2; no se creó la app, no se
compiló y no se agregó el workflow.

## Qué había

| Recurso | Estado |
|---|---|
| Java | OpenJDK 21 (sistema). Sirve para AGP 8.x; no hizo falta instalar JDK 17 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | vacíos |
| `sdkmanager` | no instalado |
| `gradle` | Gradle 8.14.3 preinstalado |
| CPU | 4 núcleos |
| Memoria | 15 GiB, sin swap |
| Disco | ~31 GB disponibles |

## Red (salida HTTPS a través del proxy del entorno)

| Host | Resultado | Para qué se necesita |
|---|---|---|
| `dl.google.com` | **403 (bloqueado)** | commandline-tools, platforms, build-tools, platform-tools y artefactos de Google Maven |
| `maven.google.com` | 200 en el índice, pero los artefactos redirigen (301) a `dl.google.com/dl/android/maven2/...` → **bloqueado** | AGP, AndroidX, Compose |
| `api.adoptium.net` | 403 (bloqueado) | JDK Temurin (no necesario, ya hay JDK 21) |
| `github.com` (releases) | 403 (bloqueado) | alternativa para Temurin |
| `plugins.gradle.org` | 200 | plugins de Gradle |
| `services.gradle.org` | 200 | distribución del Gradle wrapper |
| `repo.maven.apache.org` | 429 (rate limit) | Maven Central (Kotlin, etc.) |

Sin `dl.google.com` no hay forma de obtener ni el SDK (`android.jar`, `aapt2`,
`d8`) ni el Android Gradle Plugin, así que ningún build de Android puede
funcionar aquí.

## Qué instalé

Nada.

## Tiempos y tamaño del APK

No aplica: no hubo build.

## Opciones para desbloquear

1. **Ampliar la política de red del entorno** para permitir `dl.google.com` y
   `maven.google.com` (y revisar el rate limit de `repo.maven.apache.org`).
   Luego repetir esta prueba.
2. **Compilar en GitHub Actions** en vez de en este entorno: los runners
   `ubuntu-latest` traen Android SDK y tienen acceso a Google Maven. El entorno
   cloud serviría para editar código y empujar, y CI compilaría y publicaría el
   APK como artefacto.
3. Preinstalar el SDK en la imagen del entorno o en un script de setup con
   acceso a esos hosts (requiere igualmente que la red lo permita en algún
   momento).

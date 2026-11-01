# whisper.cpp para transcribir las notas de voz en el teléfono, sin red.
#
# Se baja el código de una versión fija y se comprueba su SHA-256: el build no
# depende de un submódulo ni de lo que haya en main ese día.
cmake_minimum_required(VERSION 3.22)
project(metalmind_voz LANGUAGES C CXX)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Cada teléfono ARM tiene otras instrucciones (dotprod, fp16, i8mm, SVE…): se
# compila una variante de la CPU de ggml por cada combinación común y al
# arrancar se carga la mejor que el procesador soporte. Compilar solo para la
# más nueva cerraría la app con SIGILL en teléfonos más viejos; solo para la
# base, la haría varias veces más lenta en los nuevos.
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    # Teléfonos de 32 bits: una sola variante (NEON). Lentos para whisper, pero
    # sin bibliotecas para su ABI Android ni dejaría instalar la app.
else()
    # x86_64 es para el emulador: una sola variante, con lo que expone su
    # CPU virtual (SSE4.2, AVX y F16C; sin AVX2 ni FMA).
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(whisper)

add_library(metalmind_voz SHARED whisper_jni.cpp)
target_link_libraries(metalmind_voz PRIVATE whisper ggml log)

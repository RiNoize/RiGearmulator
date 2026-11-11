# RiOsTIrus Android 0.2.0 alpha — Snow White

## Interfaz 0.2.0

Tres pantallas, estilo blanco como el Virus TI Snow (perillas grafito, acentos rojos, display oscuro):

- **TOCAR**: display con banco, número, categorías y nombre; PATCH ◀ ▶ recorre la lista actual de la biblioteca (una categoría filtrada funciona como set list); BANK −/+ salta de banco; ☆ marca favorito. 25 perillas de performance: osciladores, filtros, envolventes de filtro y amplificador, efectos (chorus, delay, reverb, distorsión) y master. Arrastre vertical; un toque permite escribir el valor exacto. Los valores vienen del patch cargado y se actualizan si el teclado envía CC o Program Change.
- **BIBLIOTECA**: como el patch manager del plugin OsTIrus. Colecciones (todos, favoritos, recientes, cada banco ROM con los nombres del plugin: TI A–S, TI2 A–Y, Snow A–H), categorías del patch (Category 1/2: Lead, Bass, Pad, Decay, Pluck, Acid…) con conteo, etiquetas Arp/Unison/Chorus/Phaser, búsqueda por nombre, orden por banco o nombre. «Sin duplicados» muestra cada nombre una vez (el firmware repite la mayoría de los sonidos entre TI, TI2 y Snow: 6656 patches, 3119 nombres). Tocar carga el patch; mantener apretado lo marca como favorito.
- **AJUSTES**: el banco de pruebas de 0.1.0 (firmware, modelo, audio, núcleos, MIDI, diagnóstico).

Los patches se cargan enviando el single al edit buffer, igual que el plugin. Las perillas mandan cambios de parámetro SysEx (páginas A, B y 6E), coalescidos para no saturar el firmware.


App ARM64 independiente (`com.rinoize.riostirus`) para medir si el motor de OsTIrus (Gearmulator `virusLib`, Virus TI / TI2 / Snow) llega a tiempo real en la Galaxy Tab A9. No reemplaza a RiGear ni a RiXenia. 

## Firmware

Usa el archivo del sistema operativo del Virus TI (`.bin`, 6 a 9 MiB), el mismo que OsTIrus busca en la PC. No se incluye en el repositorio ni en el APK. Se copia una vez a los datos privados de la app. El mismo archivo arranca TI, TI2 o Snow; el modelo y la frecuencia (44100 / 48000 Hz) se aplican con REINICIAR MOTOR.

## Hilos y núcleos

- TI y TI2: dos DSP, hilos `DSP DSP A` y `DSP DSP B`. El microcontrolador corre dentro del callback de audio de DSP A.
- Snow: un DSP, hilo `DSP`.
- «Núcleos» usa el helper compartido `source/Android/common/ThreadPlacement.h` (el mismo de RiXenia 0.2.1). Por defecto **Repartido**: un núcleo rápido por DSP (en la A9, 6 y 7).

El diagnóstico muestra por hilo: CPU, uso (100% = un núcleo), máscara, nice y los MIPS de cada DSP. Un DSP cerca de 100% no llega a tiempo real; en ese caso, probar reloj DSP 75%, 44100 Hz o el modelo Snow.

## Prueba sin teclado

ACORDE 4 / ACORDE 8 sostiene notas en el canal 1 hasta SOLTAR; PROGRAMA −/+ envía Program Change. Con teclado USB: CONECTAR MIDI.

## Build

`source/Android/build-riostirus-native.sh` (NDK 27.2, `-Dgearmulator_SYNTH_OSTIRUS=ON`) y luego `gradle :app:assembleRelease` en esta carpeta. CI: `.github/workflows/android-ostirus.yml`, rama `android-ostirus`. Pruebas de host: `tests/MidiBytesTest.cpp` y `RiXeniaApp/tests/ThreadPlacementTest.cpp`.

# RiOsTIrus Android 0.1.0 alpha — banco de pruebas Virus TI

App ARM64 independiente (`com.rinoize.riostirus`) para medir si el motor de OsTIrus (Gearmulator `virusLib`, Virus TI / TI2 / Snow) llega a tiempo real en la Galaxy Tab A9. No reemplaza a RiGear ni a RiXenia. No tiene editor ni biblioteca: firmware, audio, MIDI, núcleos y diagnóstico.

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

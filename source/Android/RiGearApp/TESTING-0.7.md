# RiGear 0.7 — prueba de rendimiento y controles

Esta version incluye 54 controles, distribuidos en OSC/FILTER, ENV/LFO/ARP y FX/UNISON. Los cambios se envian como SysEx de parametros del Virus (paginas 0x70/0x71). Se conserva la entrada Android MIDI y el motor/ROM del proyecto. Usar inicialmente el canal MIDI 1, porque el Virus arranca en modo Single con canal global 1; recibir mensajes de todos los canales no lo convierte en Omni.

## Primera prueba

1. Instalar RiGear-0.7-arm64.apk. Es una APK de prueba con firma de depuracion; si Android rechaza actualizar una instalacion con otra firma, desinstalar primero RiGear. Esta version aun no guarda patches editados entre sesiones.
2. Cargar el mismo ZIP, elegir el banco/patch que fallaba y conectar el mismo teclado MIDI.
3. Mantener Clock DSP en 100%, Buffer en 512, Extra DSP en 1 y Salida en -6 dB (valores iniciales). Pulsar START.
4. Tocar una nota, luego acordes de 3, 6 y mas notas, conservando el nombre del patch y observando los contadores. RESET METERS permite excluir la primera carga/calientamiento del patch de una comparacion posterior.
5. Pulsar STOP, cambiar solo Buffer a 1024 y repetir el mismo patch y acorde. Tambien existe 256 para comparar.
6. Enviar una captura de los medidores y del banco/programa al aparecer el problema. No modificar varios ajustes a la vez al comparar.

## Significado de los medidores

- **CPU app**: delta de Android Process.getElapsedCpuTime dividido por tiempo transcurrido. Suma el tiempo de CPU de los hilos de la app. 100% equivale a un nucleo logico; puede superar 100%. No es el porcentaje total de CPU de toda la tablet ni el contador interno del DSP emulado.
- **Render**: tiempo de pared de la llamada JNI que genera un bloque dividido por la duracion de audio de ese bloque. Incluye esperas y sincronizacion. El valor principal usa la ultima ventana de medicion y max conserva el peor bloque desde RESET METERS/START.
- **OV**: cantidad de bloques cuyo render tarda mas que `frames / sampleRate`. No significa por si solo que hubo un corte audible, porque puede quedar audio en cola.
- **Underruns**: contador de AudioTrack, separado de OV. RESET METERS cambia la referencia del contador mostrado, no reinicia AudioTrack mientras se esta tocando.
- **Peak / Clips**: nivel pico del ultimo bloque y cantidad acumulada de muestras que llegaron a magnitud >= 1 ANTES de la ganancia de salida. Reducir Salida no reduce ese contador de entrada: permite comparar si el problema es saturacion a la salida.
- **NaN/Inf**: muestras no finitas detectadas antes de la salida; se sustituyen por cero. No es un detector universal de audio corrupto.
- **Active**: estados Note On sin Note Off recibidos por MIDI; no mide voces DSP, colas de release ni sustain.

Los milisegundos del bloque no son la latencia total. Se muestra tambien la capacidad REAL de la cola de AudioTrack en frames. El minimo que exige Android puede ser mayor que el bloque elegido.

## Tweaks

- Buffer: 256 / 512 / 1024 frames. Cambia el tamano de cada render y la capacidad solicitada a AudioTrack. No eleva la polifonia fijada por el sintetizador.
- Clock DSP: 50 / 75 / 100 / 125 / 150 / 200%, usando `Device::setDspClockPercent`, como las opciones del plugin. No es un overclock fisico de la tablet. Subirlo puede aumentar el trabajo del emulador y empeorar el rendimiento; no se promete mayor polifonia.
- Extra DSP: 0 / 1 / 2 / 4 / 8 bloques mediante `setExtraLatencySamples`. Es independiente de la cola de Android y agrega latencia.
- Salida: 0 / -6 / -12 / -18 / -24 dB, con rampa de ganancia por bloque.

No se incluyen botones decorativos de resampling HQ/Legacy/LoFi: esta ruta sigue entregando la frecuencia nativa del Virus A/B/C (46875 Hz) a AudioTrack y no usa el resampler de escritorio. Los parametros de las perillas se muestran en unidades nativas, no en Hz/ms inventados. SYNC pide el edit buffer real durante la reproduccion. Los nombres del browser siguen siendo los de la ROM; los Program Change externos no sincronizan todavia la posicion visual del browser.

## Compilacion y limites de la prueba

La APK 0.6 anterior se genero con `assembleDebug` y CMake Debug. En 0.7, GitHub Actions compila primero TODO el motor C++ con CMake Release/NDK 27.2.12479018, verifica NDEBUG y optimizacion para Virus/DSP/JNI y luego empaqueta esas bibliotecas en la APK. `native-build.json` contiene el commit y comandos de compilacion verificados. La app informa tambien NATIVE: RELEASE usando el estado real de NDEBUG.

`RiGearRuntime.cpp` incluye el puente 0.6 en una unica unidad de compilacion para conservar su importador y parser MIDI. CMake compila solo RiGearRuntime.cpp, no ambos archivos por separado. El importador de firmware sigue seleccionando el primer firmware y primera imagen de presets del conjunto; la seleccion explicita de modelos y la validacion mas completa de pares firmware/presets siguen pendientes.

El hilo de audio es propietario de su AudioTrack: STOP no libera ni reutiliza un motor mientras un render anterior siga vivo. Los cambios de configuracion se aplican con audio detenido. Se conserva FLAG_KEEP_SCREEN_ON mientras la app es visible. Al bloquear manualmente la pantalla o salir de la app, esta version DETIENE el audio y requiere START al volver; no promete audio estable en segundo plano.

Las pruebas automatizadas verifican limites de OV para 256/512/1024, reset, datos invalidos, configuracion Release y presencia de biblioteca nativa en la APK. No prueban firmware, audio o teclado fisico en la tablet. La corrupcion con patches exigentes debe volver a evaluarse con estos contadores; 0 underruns en una prueba anterior no demostraba estabilidad para todos los patches.

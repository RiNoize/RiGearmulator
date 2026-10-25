# RiXenia Android 0.1.0 alpha

Aplicación independiente para tocar el motor **Xenia / Waldorf Microwave II–XT** de Gearmulator en Android ARM64. Identificador `com.rinoize.rixenia`: puede instalarse junto a RiGear / Osirus. Esta versión usa el motor original `xtLib`, con un DSP y sin expansión de voces.

## Primera prueba en la Galaxy Tab A9

1. Instalá `RiXenia-0.1.0-arm64.apk`.
2. En **CONFIGURACIÓN → CARGAR ROM BIN / ZIP**, elegí tu ROM completa de Microwave II/XT de **262144 bytes (256 KiB)** o el ZIP que contiene ambas mitades de 128 KiB. La app identifica las mitades por sus cabeceras y las intercala automáticamente; no necesitás descomprimir ni unir archivos. Debe incluir las wavetables. Un update del sistema operativo `.mid` o una mitad de ROM por sí sola no alcanza. Si el ZIP incluye un update MIDI, se informa que no se aplica: esta versión usa el sistema de la ROM BIN. La ROM completa se copia al almacenamiento privado de la app y se vuelve a cargar al abrirla.
3. Empezá con **buffer motor 512**, **salida 48000 Hz** y **modo Conservador**. Pulsá **INICIAR** y mantené **NOTA C4**. La ROM 2.33 probada arranca con un sonido audible: esta primera comprobación puede hacerse **sin importar bancos**. Cuando el motor informa su single actual, el LCD muestra su nombre y habilita los controles.
4. Conectá tu teclado al hub USB, elegí el puerto en CONFIGURACIÓN y pulsá **CONECTAR MIDI**. La recepción conserva los canales del teclado; el modo Single usa el canal 1 por defecto. Se transmiten sustain, modulación y pitch bend. **PANIC** libera notas y pedal.
5. En **BANCOS → IMPORTAR .SYX / .MID**, importá tus sonidos Single de Microwave II/XT para poder recorrerlos. La biblioteca A/B contiene únicamente sonidos importados; no se extraen bancos de fábrica de la ROM. También se conservan los dumps de waves y wavetables incluidos en el archivo. Los mensajes Multi, Global, Mode y de otros sintetizadores se omiten, informando su cantidad.
6. Seleccioná un sonido en la lista y pulsá INICIAR. Los botones **PATCH − / +** recorren los sonidos importados, saltando posiciones vacías. **BANK − / +** alternan A/B, conservando el programa si existe o eligiendo el primer sonido de ese banco; si el banco está vacío se informa y se selecciona su primera posición como destino de importación, mientras el motor conserva su sonido actual. Cada banco admite 128 programas. Un patch importado como *edit buffer* se guarda en la posición que estaba seleccionada al importar.

El motor funciona internamente a **40000 Hz**. La salida de 48000 Hz usa conversión continua 6/5 en C++; un bloque de 512 frames requiere hasta **615 frames de salida**. La opción Nativa conserva 40000 Hz, si Android y la ruta de audio la aceptan.

## Interfaz y estado

- Pantalla encendida mientras la aplicación está visible; ambas orientaciones horizontales disponibles.
- Carga de ROM, archivos y arranque del motor fuera del hilo de interfaz.
- Los bancos importados y la selección A/B/programa se conservan al reiniciar. Importar otra vez reemplaza el sonido de la misma dirección.
- El LCD distingue un sonido de biblioteca, por ejemplo `A001 · SINGLE`, del sonido que está actualmente en el motor cuando la posición de importación está vacía: `ACTUAL · destino A001`. Un sonido inicial de la ROM no se presenta como si fuese un preset importado.
- Nueve controles básicos del XT: wavetable, inicio de waves 1/2, cutoff de filtros 1/2, resonancia, ataque/sustain/release de amplitud. Se usan los índices reales de `parameterDescriptions_xt.json`. Los cambios son temporales; cambiar de patch recupera el sonido importado. Esta versión no guarda ediciones.
- Volumen independiente del patch, de −24 a 0 dB; valor inicial −6 dB.
- Audio detenido al salir de la aplicación. Después de volver o importar archivos, pulsá INICIAR.

## Medidores y buffers

**Render %** mide el tiempo de cálculo de un bloque respecto de la duración de audio del motor, incluyendo la conversión de frecuencia. **OV** cuenta bloques que tardaron más que ese plazo. **CPU app %** mide tiempo de CPU de todo el proceso; 100% equivale a un núcleo completo y puede superar 100% con varios hilos. **Underruns** son cortes informados por AudioTrack. Ninguno de estos valores representa por sí solo la latencia de extremo a extremo.

El modo Conservador prioriza margen de audio. Baja latencia permite elegir el buffer de salida y lo aumenta en pasos de 256 frames si Android informa underruns. El diagnóstico se recoge fuera del bucle de render, con actualización visual una vez por segundo. La expansión de voces y el reloj DSP ajustable no se exponen en esta primera versión.

## Verificación

Las pruebas de código verifican lectura SysEx/MIDI, nombres y direcciones A/B, restauración de bancos, rechazo de archivos truncados/checksum incorrecto, SysEx divididos entre eventos SMF, combinación de las mitades de ROM dentro de ZIP, límites de descompresión y tamaño correcto de los buffers de conversión. Los ZIP se leen en memoria; ninguna ruta interna se escribe al disco. El workflow también compila las API Java contra Android 35 y el motor ARM64 en Release.

Para esta primera versión se probó además el puente JNI completo con la ROM 2.33 proporcionada por el usuario en un host Linux x86_64. Pasaron tres ciclos independientes de arranque, selección A/B comprobada por su audio, edición de cutoff, sustain, liberación de pedal, acordes y PANIC, salida nativa y conversión 6/5, saturación de cola y cierre en reposo. La ROM y los fixtures de esa prueba no se incorporan al repositorio. Son comprobaciones del motor real; no miden el rendimiento ni la latencia de la tablet.

No se incluye ROM ni bancos protegidos. La validación de audio y rendimiento en Android requiere una prueba real en la tablet. Para esa prueba: tocar notas y acordes, sostener con pedal durante varios minutos, cambiar patches, rotar 180°, detener/iniciar y revisar Render, OV y underruns. Si hay cortes, probar buffer motor 1024 y modo Conservador antes de reducir la latencia.

## Compilación

Desde la raíz del repositorio, `source/Android/build-rixenia-native.sh` compila y coloca la librería nativa ARM64 usando Android SDK/NDK. Después, desde `source/Android/RiXeniaApp`, ejecutar `gradle --no-daemon :app:assembleRelease` para empaquetar el APK. La compilación de GitHub Actions usa el secreto permanente `RIGEAR_SIGNING_KEY_BASE64` y un `versionCode` creciente. La firma permite actualizar RiXenia sin reinstalar ni perder los archivos importados.

Código de Gearmulator y modificaciones bajo las condiciones de licencia del repositorio; la aplicación conserva los avisos y fuentes correspondientes.

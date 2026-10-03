# RiGear 0.8 — latencia sin cambiar el motor de sonido

La version 0.7 queda preservada en la rama `android-v0.7-reference`, commit `8cdc4e71de9ef3a78f95c1971ebb82d832dca458`. La 0.8 mantiene la frecuencia del Virus, el render Release, MIDI y los 54 controles. No incorpora otro sinte, resampling nuevo, ni guardado de ROM/patches entre sesiones.

## Instalacion

Instalar `RiGear-0.8-arm64.apk`. Es una APK de prueba firmada con una clave debug del runner, no una clave de distribucion. Si Android rechaza la actualizacion por una firma diferente, conservar las ROM fuera de la app, desinstalar RiGear y volver a instalar. Las ediciones de patches aun no se guardan. Mantener la APK 0.7 como respaldo; restaurarla puede requerir desinstalar la 0.8.

## Ajustes independientes

| Ajuste | Significado |
|---|---|
| Motor | Bloque de render: 256, 512 o 1024 frames. |
| Modo salida: Conservador | Conserva la politica de AudioTrack de 0.7: capacidad solicitada = mayor entre minimo Android x2 y cuatro bloques. No reduce el buffer util. |
| Modo salida: Baja latencia | Usa `setBufferSizeInFrames` para solicitar un buffer util menor. No obliga a Android a conceder una ruta LOW_LATENCY. |
| Salida | 256, 512, 768, 1024, 1536, 2048 o 4096 frames. Solo se usa en modo Baja latencia, independientemente de Motor. |
| Extra DSP | **Frames absolutos**: 0, 128, 256, 512, 1024, 2048, 4096 u 8192. Cambiar Motor no cambia esta cantidad. |
| Clock / Ganancia | Sin cambios respecto de 0.7. Mantener inicialmente 100% / -6 dB. |

Valores iniciales: Motor 512, modo Conservador, Extra DSP 512, clock 100%, ganancia -6 dB. Esto corresponde a la comparacion solicitada con 0.7 a 512 / Extra DSP 1. El selector Salida queda deshabilitado en modo Conservador.

En Baja latencia se precarga solo el buffer **efectivo**, no toda la capacidad asignada. En Android API 31+ tambien se ajusta el umbral de arranque a ese buffer efectivo. Las escrituras siguen siendo bloqueantes y no se descartan bloques cuando aparece OV. La aplicacion no aumenta automaticamente el buffer por OV/underruns. Al cambiar la ruta de salida Android puede alterar sus tamanos; se muestran los valores actualizados que reporta.

Todos los ajustes se aplican con STOP -> START. Soltar las teclas y el pedal al reiniciar: el arranque envia Panic y reinicia los controladores del motor. No se promete menor latencia hasta medir y probar en la tablet.

## Prueba controlada

1. Cargar el mismo VirusBC.zip, patch A 002 Avenues JS, y conectar el mismo teclado por canal 1.
2. Probar los valores iniciales Conservador / Motor 512 / Extra DSP 512. Escuchar acordes, notas cortas y sustain.
3. STOP. Cambiar **solo** Modo salida a Baja latencia, con Salida 1024. START. Comparar respuesta al tocar y continuidad. Anotar Salida pedida, efectiva, capacidad y Pendiente.
4. Si esta limpio, STOP -> Salida 512 -> START. Motor sigue en 512 y Extra DSP en 512.
5. Luego comparar Motor 256 con la misma Salida y el mismo Extra DSP 512. Asi cambia solo el bloque, no el margen extra del DSP.
6. Si hay clicks o underruns, volver al ultimo buffer de salida limpio o al modo Conservador. Bajar Extra DSP (256 o 0) es una prueba posterior y separada; no hacerlo a la vez que las anteriores.

Usar RESET METERS despues de la carga inicial para comparar ventanas similares. Mandar captura durante el acorde con nombre del patch, ajustes, modo Android concedido, tamanos reales, Pendiente, Render, OV/s y underruns.

## Indicadores

**Pendiente**: estimacion por `samplesAccepted / 2 - unsignedExtendedPlaybackHead`, en frames y milisegundos. Incluye las escrituras parciales y el silencio de precarga. Se observa desde el hilo de audio aproximadamente cuatro veces por segundo, despues de escribir, por lo que puede mostrar la cola cerca de su maximo. Es el retraso estimado hasta la posicion reportada por AudioTrack, NO una medicion completa tecla-a-sonido ni una medida garantizada de las colas fisicas del hardware. La posicion puede tener granularidad propia. No incluye una medicion de MIDI, DSP, procesamiento del sistema, DAC o Bluetooth.

El contador de posicion de 32 bits se extiende para soportar su rollover. Si hay un reinicio/salto hacia atras incompatible con rollover o una posicion mayor que lo escrito, se muestra `no disponible` hasta una nueva sesion. Un dato con mas de un segundo sin refrescar se marca viejo; tras STOP se conserva como `ultima`. RESET METERS **no** altera el origen de la posicion ni la cola.

**Salida pedida / efectiva / capacidad**: distingue la seleccion de RiGear del tamano util concedido y del maximo asignado. Si Android no acepta exactamente lo pedido, la pantalla informa lo que realmente concedio. El rechazo de la llamada detiene el inicio con un error, sin un fallback oculto.

**Android NORMAL / LOW_LATENCY / POWER_SAVING**: resultado real de `getPerformanceMode()`, no simplemente el flag solicitado. AUDIO INFO muestra ademas ruta, umbral de arranque si esta disponible, frames aceptados y las propiedades orientativas de salida del sistema. NORMAL no indica fallo del sinte: significa que Android no concedio esa ruta de baja latencia en la configuracion actual.

**CPU app / Render / Max / OV / Underruns / Peak / Clips / NaN-Inf**: conservan las definiciones de 0.7. CPU suma hilos de la app y 100% representa un nucleo; Render es tiempo de pared del bloque, incluyendo esperas. **OV/s** es el incremento de OV por segundo real durante la ventana reciente, no la cantidad de cortes. Un OV no prueba un underrun. El maximo y OV acumulado se conservan hasta RESET METERS o nueva sesion.

**Teclas USB presionadas**: estados de teclas segun Note On/Off de USB; no incluye teclado tactil ni cuenta voces internas. **Sustain canal 1**: ultimo estado CC64 recibido por ese canal durante el audio activo, umbral 64. Las teclas pueden marcar 0 con Sustain ON y el acorde seguir sonando. Panic, conexion nueva y nuevo arranque reinician los estados de diagnostico en concordancia con el motor; RESET METERS no lo hace. No es una lectura electrica del pedal ni un contador de voces.

## Verificacion automatizada y limites

El workflow comprueba Release/NDEBUG, compila todas las clases con el Android SDK y ejecuta tests JVM: limites OV para 256/512/1024, OV/s, resets, contabilidad de cola, escrituras parciales, rollover de posicion, mensajes MIDI partidos/running status, cinco Note On/Off con sustain, CC64 por canal, velocity-zero Note Off y reset de controladores. Verifica APK versionName 0.8.0/versionCode 9 y biblioteca ARM64 empaquetada.

Estas pruebas no ejecutan el Virus en una tablet, no miden latencia acustica, no prueban todas las ROM ni garantizan que la salida acepte buffers pequenos. Siguen pendientes la seleccion explicita de ROM B/C, guardado de patches y sincronizacion visual del banco/programa cuando se cambia por MIDI externo. No se modifican los submodulos DSP ni el sintetizador de upstream para esta prueba.

FLAG_KEEP_SCREEN_ON se mantiene mientras RiGear es visible. Salir o bloquear manualmente detiene el audio y requiere START al volver. No hay audio de fondo prometido.

# RiGear 0.9 — salida a 48 kHz con conversion continua

La 0.8 queda preservada en `android-v0.8-reference`, commit `340c360a1090074256a79c33d17d2aa477e73f97`. La 0.9 conserva Osirus/Virus, firmware importado, MIDI USB, sustain, los 54 controles y compilacion Release. No cambia el clock del Virus para adaptar la frecuencia de audio.

## Nuevo selector: Hz salida

- **Nativa**: entrega la frecuencia original del motor a AudioTrack. En Virus B es 46875 Hz. Omite completamente el nuevo conversor; sirve para comparar con la 0.8.
- **48000**: genera audio del Virus a 46875 Hz y lo convierte continuamente en C++ a 48000 Hz. AudioTrack recibe realmente muestras a 48000 Hz, no las mismas muestras reproducidas mas rapido.

Se sigue solicitando PERFORMANCE_MODE_LOW_LATENCY. Android puede conceder NORMAL; ni la conversion ni el selector de buffer garantizan una ruta de baja latencia. El monitor y AUDIO INFO muestran el modo concedido, no el solicitado. Esta version NO sustituye AudioTrack por Oboe/AAudio.

Valores iniciales: Hz salida 48000, Motor 512, modo Conservador, Extra DSP 512 frames del Virus, Clock 100%, Ganancia -6 dB. Salida 1024 solo se aplica al elegir modo Baja latencia. Todas las modificaciones de configuracion requieren STOP -> START, sin notas ni pedal presionados al reiniciar.

## Prueba en la tablet

1. Instalar la APK, cargar el mismo VirusBC.zip, elegir A 002 Avenues JS y conectar el Novation por canal MIDI 1.
2. Con 48000 / Conservador comprobar que el timbre y la afinacion resulten correctos. Mirar `Virus 46875 Hz -> AudioTrack 48000 Hz` y el modo Android concedido.
3. STOP. Cambiar solo a Baja latencia, Salida 1024. START y comparar el mismo acorde, notas cortas y sustain.
4. Si suena limpio, probar Salida 768 y luego 512. Conservar Motor, Extra DSP y Clock para aislar el cambio.
5. Luego comparar Motor 256, manteniendo Extra DSP 512 y la ultima Salida limpia. No reducir Extra DSP a la vez.
6. Si aparecen cortes, volver a un buffer mayor o a Conservador. Para comparar sin conversion, STOP -> Hz salida Nativa -> START. No hay un incremento automatico ni fallback oculto por underruns.

Enviar captura de AUDIO INFO con las dos frecuencias, modo Android, buffer efectivo, Pendiente, Render, OV/s, underruns y tiempo SRC. No afirmar una mejora de latencia hasta probarla en la tablet; estos tests no incluyen hardware ni firmware real ejecutandose.

## Conversor implementado

Filtro FIR polifasico de 96 coeficientes y 128 fases, con ventana Blackman y corte normalizado 0.94 de la frecuencia Nyquist de entrada. Relacion exacta 128 salidas por 125 entradas. La fase y el historial se conservan entre llamadas; no se redondea y reinicia cada bloque. Los coeficientes se calculan una vez con el audio detenido, no durante el render. El bucle de conversion no asigna memoria dinamica ni evalua funciones trigonometricas.

Un bloque nativo de 256 frames produce 262 o 263 frames de salida; 512 produce 524 o 525. Java escribe solo las muestras validas, incluye escrituras parciales y no reproduce el espacio sobrante del array. Se copia una sola vez a Java, despues de convertir. El historial se reinicia al preparar una sesion nueva, no al cambiar de patch, pulsar PANIC ni RESET METERS.

El filtro agrega un retardo nominal de 47.5 muestras de entrada, aproximadamente 1.013 ms. No es la latencia total de la app. Atenua el extremo superior del espectro para limitar imagenes de conversion; no se promete equivalencia bit a bit entre las dos rutas. La ganancia y el limitador de seguridad siguen activos. Clips/Peak se observan en la senal del Virus antes de ganancia/SRC, como antes; no son un analizador completo del audio final.

El conversor nuevo admite especificamente 46875 -> 48000. Si una ROM reporta otra frecuencia no compatible, el arranque informa error e indica usar Nativa en lugar de alterar silenciosamente afinacion o duracion. Una ROM ya a 48000 pasa sin conversion. No se reconfigura la frecuencia en caliente por cambios de ruta.

## Medidores y unidades

- **Motor y Extra DSP**: frames del Virus. El denominador del Render y OV sigue siendo `framesMotor / frecuenciaVirus`.
- **Render**: tiempo total de la llamada nativa, incluido SRC y copia JNI, no solo el DSP.
- **SRC ultimo / max**: tiempo de pared solo de conversion y limitacion final. Ya esta incluido dentro de Render; no sumarlo de nuevo. No equivale a porcentaje de CPU de toda la tablet. El maximo se reinicia con RESET METERS; no la fase del conversor.
- **Salida, efectiva, capacidad, Pendiente y umbral de arranque**: frames de AudioTrack, a la frecuencia de salida seleccionada. A 48000, 1024 frames son 21.33 ms y 512 son 10.67 ms de contenido, no mediciones completas tecla-a-sonido.
- **Pendiente** conserva sus limitaciones de la 0.8: estimacion a partir de frames escritos menos playback head, no incluye todas las colas fisicas ni MIDI, DSP o DAC. Se muestra el modo Android real.
- **Sustain y teclas USB**: mismo diagnostico de la 0.8, separado de voces DSP.

## Verificacion

Tests C++ ejecutados con AddressSanitizer y UndefinedBehaviorSanitizer: relacion exacta de frames, continuidad bit a bit del mismo render con bloques de 1/125/256/512/1024, tonos de 100/1000/15000/20000 Hz frente a una referencia temporal con retardo del filtro, ausencia de crosstalk, ganancia DC, limites de buffers, reinicios deterministas y cinco segundos de contabilidad sin deriva. Son pruebas sinteticas del conversor, no pruebas de Osirus en el dispositivo.

El workflow conserva tests JVM de sustain, MIDI fragmentado/running status, rollover/cola, OV/s y reinicio de medidores; compila Java con SDK 35; verifica Release/NDEBUG; compila y empaqueta ARM64; comprueba version 0.9.0 / versionCode 10. Los informes y comandos de compilacion van dentro del ZIP.

La APK sigue usando firma debug del runner para prueba directa. Si Android rechaza actualizar por diferencia de firma, conservar ROMs fuera de la app, desinstalar la anterior y reinstalar; las ediciones de patches aun no se guardan. Guardar la APK 0.8 para volver atras. La pantalla permanece encendida mientras RiGear es visible; al salir o bloquear manualmente se detiene audio y se requiere START al volver.

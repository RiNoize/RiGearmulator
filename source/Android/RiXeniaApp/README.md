# RiXenia Android 0.1.1 alpha

Aplicación independiente para tocar el motor **Xenia / Waldorf Microwave II–XT** de Gearmulator en Android ARM64. Identificador V2 `com.rinoize.rixenia.v2`: puede instalarse junto a RiGear / Osirus y RiXenia 0.1.0. Usa el motor original `xtLib`, con un DSP y sin expansión de voces.

## Instalación sin perder la versión anterior

Instalá `RiXenia-0.1.1-arm64.apk`: aparece como **RiXenia V2**. La firma anterior fue reemplazada por la V2 utilizada en RiGear. Por eso no se intenta actualizar la app 0.1.0 con un certificado diferente: **no desinstales la anterior**. En V2 necesitás importar una vez tu ROM y tus bancos; no se leen automáticamente los datos privados de la app anterior. Las futuras actualizaciones V2 mantienen sus datos.

En CONFIGURACIÓN → CARGAR ROM BIN / ZIP, seleccioná tu ROM completa de 262144 bytes o el ZIP con las dos mitades de 128 KiB. Se combinan automáticamente. El update MIDI incluido no se aplica; se usa el sistema de la ROM BIN. La ROM queda guardada internamente.

En BANCOS → IMPORTAR .SYX / .MID importá tus sonidos Single. A/B admite 128 sonidos por banco. Una nueva importación reemplaza las posiciones coincidentes; los recursos wave y wavetable también se conservan. Los mensajes Multi, Global, Mode y de otros modelos se omiten. Los bancos ZIP todavía necesitan descomprimirse.

Para comparar con las pruebas anteriores de la Galaxy Tab A9, seleccioná **buffer motor 1024, salida 48000 Hz y modo Conservador**. Pulsá INICIAR. La ROM 2.33 permite probar un sonido inicial con NOTA C4. Conectá el teclado USB desde CONFIGURACIÓN; probá primero con canal 1.

## Corrección de cambios de patch

- Se alternan los lotes MIDI y de control: notas, CC o Clock continuos no postergan indefinidamente el SysEx del patch. Se mantiene la separación entre dumps completos para no saturar el firmware.
- Entrar en modo Single no se repite en cada selección. Las selecciones rápidas sustituyen los dumps de edit buffer todavía pendientes; no se descartan notas ni mensajes del pedal.
- El display distingue CARGANDO, PENDIENTE · INICIAR y SIN CONFIRMAR. La confirmación requiere una respuesta real del firmware con los 256 bytes del sonido solicitado, no la caché optimista del editor.
- Se informa el tiempo de confirmación y la cola MIDI/SysEx. Ese tiempo no es la latencia audible: puede haber audio previamente generado en el buffer de salida.
- A los tres segundos sin confirmación se informa, pero se aceptan respuestas tardías y nuevas selecciones. La edición de parámetros queda deshabilitada mientras se aplica el patch.

Los recursos wave/wavetable mantienen su orden. Una restauración grande todavía puede tardar; no se omiten dependencias para aparentar un cambio instantáneo.

## Audio e interfaz

El motor funciona a 40000 Hz. La salida de 48000 Hz usa conversión continua 6/5. Nativa solicita 40000 Hz a Android, sin garantizar que sea la frecuencia física de salida.

Se mantienen sustain, modulación, pitch bend y PANIC, los nueve controles de síntesis y volumen de −24 a 0 dB. Las ediciones son temporales. La pantalla permanece encendida y admite ambas posiciones horizontales. Al salir se detiene el audio; al volver o importar archivos, pulsá INICIAR. ROM, bancos y selección permanecen guardados.

Render mide tiempo por bloque, no CPU pura. OV es acumulativo y OV/s indica bloques tardíos por segundo. CPU app suma el tiempo de los hilos; 100% equivale a un núcleo. Underruns informa vaciados de AudioTrack. Conservador da margen a costa de latencia. Los diagnósticos se recogen fuera del render.

## Verificación y límites

Las pruebas automáticas cubren parsers, checksums, tamaños, conversión de frecuencia, ciclo de vida y planificación MIDI. La regresión nueva verifica tráfico continuo, buffers 256/512/1024, última selección vigente, buffers PMR del motor, rechazo de respuestas antiguas y confirmación tardía.

Las pruebas con ROM 2.33 documentadas para 0.1.0 no sustituyen la validación de esta revisión. No se afirma una reducción medida de CPU ni una latencia concreta en la tablet. La aceptación requiere cambiar sonidos con MIDI conectado, con y sin Clock, comprobando audio, confirmación, sustain y underruns.

No se incluye firmware ni bancos protegidos. Los artefactos contienen los cambios Android y las revisiones de los submódulos; el repositorio permite obtener las fuentes completas.

## Compilación

Ejecutar `bash source/Android/build-rixenia-native.sh` con SDK/NDK instalado. Luego, desde `source/Android/RiXeniaApp`, ejecutar `gradle --no-daemon :app:assembleRelease`. GitHub Actions exige `RIGEAR_SIGNING_KEY_BASE64_V2` y comprueba su certificado, sin guardar claves privadas en caché ni generar firmas aleatorias para versiones distribuidas. El versionCode es creciente.

Código y modificaciones bajo las condiciones de licencia del repositorio.

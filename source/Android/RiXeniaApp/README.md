# RiXenia Android 0.1.2 alpha

Aplicación independiente ARM64 para el motor Xenia / Waldorf Microwave II–XT de Gearmulator. Usa xtLib, un DSP, reloj 100%, sin expansión de voces.

## Actualización

Instalar `RiXenia-0.1.2-arm64.apk` sobre **RiXenia V2 0.1.1**, sin desinstalar ni borrar datos. Mantiene `com.rinoize.rixenia.v2`, el certificado V2, la ROM y los bancos importados. La app original 0.1.0 sigue siendo independiente.

## Cambios 0.1.2

Se evitan el mutex y la notificación de reanudación cuando el DSP ya corre, y el bloqueo por muestra cuando no hay una orden de halt. No se repite la señal de inicio de audio en cada frame activo. Se conserva el despertar bajo mutex al iniciar cada bloque, las notificaciones entre bloques y los predicados de halt/cierre. No se cambia el reloj DSP, la polifonía ni el firmware, ni se suspende el motor al detectar silencio.

CONFIGURACIÓN incorpora **COPIAR DIAGNÓSTICO**. Guarda hasta 32 grupos de eventos: intervalo de observación, incremento y total de underruns, buffer antes/después y capacidad, cola aproximada, pico y media de render de esa ventana, OV y CPU app. El monitor consulta nominalmente cada 500 ms; los intervalos reales pueden ser mayores. El registro no indica el instante exacto de cada underrun ni prueba su causa. El pico incluye sólo bloques terminados en la ventana. El formato y el historial se elaboran fuera del hilo de render, sin firmware ni dumps de patches.

La pantalla muestra el pico reciente y el tiempo sin nuevos underruns observados. No se ocultan ni reinician los contadores acumulativos. El historial está acotado. La política AUTO no cambia: sólo aumenta al observar nuevos underruns; su capacidad depende del tamaño solicitado, como antes.

Se agregan opciones de salida 2304 y 3072 frames sin reinterpretar las preferencias anteriores; 4096 conserva su valor. Para comparar con la sesión reportada: **motor 512, 48000 Hz, Baja latencia + AUTO, salida solicitada 2304**. Es el tamaño efectivo alcanzado por la sesión anterior. No es una promesa de latencia total de 48 ms ni una configuración universal. Si funciona estable, otra prueba separada puede reducirlo.

## ROM y biblioteca

CONFIGURACIÓN → CARGAR ROM BIN / ZIP acepta una ROM completa de 262144 bytes o un ZIP con dos mitades de 128 KiB que se combinan automáticamente. Debe incluir las waves. Un update MIDI solo no alcanza y no se aplica si está en el ZIP. La ROM se guarda internamente.

BANCOS → IMPORTAR .SYX / .MID importa Singles Microwave II/XT y sus recursos wave/wavetable. Los ZIP de bancos requieren descomprimirse. A/B contiene 128 posiciones por banco; nuevas importaciones reemplazan direcciones coincidentes. Los bancos y la selección quedan guardados sin necesitar el pendrive. No se extrae una biblioteca de fábrica desde la ROM. Los mensajes Multi/Global/Mode y otros modelos se omiten.

## Audio, MIDI y patch

Motor interno a 40000 Hz; 48000 Hz usa conversión continua 6/5. Nativa pide 40000 Hz a Android sin garantizar que sea la frecuencia física. Se mantienen sustain, modulación, pitch bend y PANIC, nueve controles temporales de síntesis y volumen de -24 a 0 dB. No se guardan ediciones de patch.

Se conservan las correcciones 0.1.1: reparto entre MIDI y control sin postergar indefinidamente patches, no repetir modo Single, última selección pendiente vigente y confirmación de los 256 parámetros por respuesta real del firmware, no por caché del editor. El tiempo de confirmación no equivale a latencia audible. Una transferencia grande de waves/tablas aún puede tardar; no se saltan dependencias.

La pantalla permanece encendida y permite ambas orientaciones horizontales. Salir de la app detiene el audio; al volver, pulsar INICIAR. Render mide tiempo por bloque, CPU app suma los hilos (100% equivale a un núcleo), OV/s cuenta bloques fuera de plazo y underruns informa vaciados de AudioTrack.

## Verificación y límites

CI verifica parsers, tamaños, SRC, despachador MIDI, ciclo de vida y el nuevo diagnóstico: conservación de totales, grupos de underruns, intervalos y límite de historial. Las pruebas de cierre incluyen reanudación sin halt y despertar de un DSP detenido.

El motor y el puente JNI se ensayaron en Linux x86_64 con ROM 2.33 del usuario: audio finito y audible, confirmaciones reales bajo Clock/CC, buffers 256/512/1024, 40/48 kHz, sustain/PANIC, reanudación y cierre. Esas pruebas no reproducen AudioTrack ni la carga de la Galaxy Tab A9. No se afirma que esta revisión elimine todos los underruns ni un ahorro concreto de CPU en Android. Se requiere una prueba prolongada en la tablet, conservando el informe antes de reiniciar la sesión.

No se incluyen firmware ni bancos protegidos. Código y modificaciones bajo las licencias del repositorio.

## Compilación

Con SDK/NDK: `bash source/Android/build-rixenia-native.sh`; después, en `source/Android/RiXeniaApp`, `gradle --no-daemon :app:assembleRelease`. CI exige el secreto permanente `RIGEAR_SIGNING_KEY_BASE64_V2`, valida el certificado del APK final y su alineación de 16 KiB. No almacena claves privadas en caché ni inventa claves nuevas para actualizaciones. VersionCode creciente. Los artefactos contienen fuentes Android y revisiones de submódulos; el repositorio y sus submódulos contienen el resto de las fuentes.

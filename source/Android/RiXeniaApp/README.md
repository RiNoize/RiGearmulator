# RiXenia Android 0.1.1 alpha

Aplicación independiente para tocar el motor **Xenia / Waldorf Microwave II–XT** de Gearmulator en Android ARM64. Identificador `com.rinoize.rixenia`: puede instalarse junto a RiGear / Osirus. Esta versión usa el motor original `xtLib`, con un DSP y sin expansión de voces.

## Primera prueba en la Galaxy Tab A9

1. Instalá `RiXenia-0.1.1-arm64.apk` sobre RiXenia, sin desinstalar la versión anterior.
2. En **CONFIGURACIÓN → CARGAR ROM BIN / ZIP**, elegí tu ROM completa de Microwave II/XT de **262144 bytes (256 KiB)** o el ZIP que contiene ambas mitades de 128 KiB. La app identifica las mitades por sus cabeceras y las intercala automáticamente. Un update `.mid` o una mitad por sí sola no alcanza. El update MIDI incluido en un ZIP no se aplica; se utiliza el sistema de la ROM BIN. La ROM completa queda guardada en el almacenamiento privado.
3. Para comparar con las pruebas anteriores de la Tab A9, usá **buffer motor 1024**, **salida 48000 Hz** y **modo Conservador**. Pulsá **INICIAR**. La ROM 2.33 permite probar un sonido inicial mediante **NOTA C4** sin importar bancos.
4. Conectá tu teclado al hub USB, elegí el puerto y pulsá **CONECTAR MIDI**. La recepción conserva los canales del teclado; probá primero con canal 1. Se transmiten sustain, modulación y pitch bend. **PANIC** libera notas y pedal.
5. En **BANCOS → IMPORTAR .SYX / .MID**, importá tus sonidos Single. La biblioteca A/B contiene los sonidos importados; no se extraen bancos de fábrica de la ROM. Se conservan también waves y wavetables incluidas. Los mensajes Multi, Global, Mode y de otros sintetizadores se omiten y se informa su cantidad.
6. **PATCH −/+** recorre los sonidos importados. **BANK −/+** alterna A/B. Cada banco admite 128 posiciones; una nueva importación reemplaza el sonido de la misma dirección. Una posición vacía sirve como destino de importación, mientras el motor conserva su sonido actual.

## Corrección de cambios de patch

- Se corrige la prioridad de las colas: un flujo constante de notas, CC o MIDI Clock no puede postergar indefinidamente el SysEx del patch. Se alternan lotes MIDI y control, conservando la separación de mensajes completos para no saturar el firmware.
- Entrar en modo Single no se repite en cada selección. Las selecciones rápidas sustituyen los dumps de edit buffer aún pendientes; no se descartan notas ni mensajes del pedal.
- El display distingue **CARGANDO**, **PENDIENTE · INICIAR** y **SIN CONFIRMAR**. No anuncia un sonido como activo a partir de la caché optimista del editor: la confirmación requiere una respuesta real del firmware con los 256 bytes del sonido solicitado.
- Se muestran el tiempo de confirmación y las cantidades de mensajes pendientes. Ese tiempo **no mide la latencia audible**: puede haber audio previamente generado en el buffer de salida.
- Si no hay confirmación a los tres segundos se informa, pero se siguen admitiendo respuestas tardías y nuevas selecciones. Mientras el cambio está pendiente se deshabilita la edición de parámetros.

Los recursos de waves/tablas mantienen su orden al importar/restaurar. Una importación grande todavía puede requerir tiempo; esta versión no salta dependencias para aparentar un cambio instantáneo.

## Audio e interfaz

El motor funciona internamente a **40000 Hz**. La salida de **48000 Hz** usa conversión continua 6/5 en C++. La opción Nativa solicita 40000 Hz a Android; no garantiza que la salida física trabaje a esa frecuencia.

Se mantienen los controles de wavetable, inicio de waves 1/2, cutoff de filtros 1/2, resonancia y ataque/sustain/release de amplitud. Las ediciones son temporales; no se guardan en esta alpha. Volumen independiente del patch, de −24 a 0 dB, inicialmente −6 dB.

Pantalla encendida y ambas posiciones horizontales. La carga y el arranque están fuera del hilo de interfaz. Al salir de la aplicación se detiene el audio; al volver o importar archivos, pulsá INICIAR. La ROM, los bancos y la selección permanecen guardados. No desinstales ni borres los datos para actualizar.

**Render %** mide tiempo de procesamiento de bloques, no CPU pura. **OV** cuenta bloques que superaron su plazo y **OV/s** indica su frecuencia reciente. **CPU app %** suma los hilos del proceso; 100% equivale al tiempo de un núcleo. **Underruns** es el contador de falta de datos de AudioTrack. El modo Conservador agrega margen de buffer a costa de latencia. El diagnóstico se recoge fuera del render.

## Verificación y límites

Las pruebas automáticas cubren lectura SysEx/MIDI/ROM ZIP, checksums y tamaños, conversión 40→48 kHz, cierre del motor y la nueva planificación MIDI. La regresión nueva comprueba MIDI continuo, buffers 256/512/1024, última selección vigente, rechazo de respuestas antiguas, timeout y confirmación tardía.

Las pruebas con ROM 2.33 en Linux documentadas para 0.1.0 son antecedentes; no sustituyen la validación de esta revisión en Android. No se afirma una reducción medida de CPU ni una latencia concreta en la tablet. La prueba de aceptación es cambiar de patch con MIDI conectado, con y sin Clock, y comprobar sonido, confirmación, sustain y underruns.

No se incluye firmware ni bancos protegidos. La compilación publica un snapshot completo de las fuentes utilizadas, incluidos los submódulos necesarios, sin claves de firma.

## Compilación

Desde la raíz, ejecutar `bash source/Android/build-rixenia-native.sh` con Android SDK/NDK instalado. Luego, desde `source/Android/RiXeniaApp`, ejecutar `gradle --no-daemon :app:assembleRelease`. GitHub Actions utiliza `RIGEAR_SIGNING_KEY_BASE64` cuando está disponible y un `versionCode` creciente.

Código de Gearmulator y modificaciones bajo las condiciones de licencia del repositorio.

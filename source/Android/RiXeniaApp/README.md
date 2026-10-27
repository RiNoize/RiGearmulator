# RiXenia Android 0.2.0 alpha — Cyan Studio

Aplicación independiente ARM64 para Xenia / Waldorf Microwave II–XT de Gearmulator. Usa xtLib, un DSP, reloj 100%, sin expansión de voces.

## Actualización

Instalar `RiXenia-0.2.0-arm64.apk` sobre **RiXenia V2 0.1.2**, sin desinstalar ni borrar datos. Mantiene `com.rinoize.rixenia.v2`, el certificado V2, la ROM, bancos y preferencias. La app original 0.1.0 sigue siendo independiente.

## Interfaz 0.2.0

Implementa la primera propuesta aprobada: panel oscuro, indicadores cian, LCD azul central y botones BANK/PATCH junto al display. Cinco paneles nativos: oscilador/wavetable, filtros, envolventes, modulación y salida. La biblioteca y configuración conservan sus funciones y reciben el mismo estilo de botones. No se cambia el motor nativo ni la política AudioTrack/AUTO de 0.1.2.

Las perillas se arrastran verticalmente. Un toque abre entrada exacta del valor MIDI; flechas de un teclado y accesibilidad permiten incrementar/decrementar. La actualización del estado no envía MIDI. Los controles se deshabilitan durante la carga de un patch y se reactivan tras la confirmación existente del firmware.

Wavetable usa los nombres e índices reales del catálogo XT. El gráfico muestra las posiciones de inicio W1/W2, **no** una forma de onda extraída del firmware. El pequeño dibujo del LFO es un esquema de la forma seleccionada, no un osciloscopio ni su fase en tiempo real. No se inventan medidores de voces ni consumos de CPU.

F1/F2 seleccionan los filtros reales; AMPLITUD/FILTRO muestran sus cuatro parámetros ADSR; LFO 1/2 permiten velocidad, forma, sincronización, delay, simetría y humanize. La matriz abre las 16 rutas reales (fuente, cantidad bipolar y destino), sin sustituir ninguna ruta al abrir/cancelar. AFINACIÓN/MIX expone semitonos/detune de ambos osciladores y sus niveles de mezcla, ring modulation y ruido. Se mantienen los nombres propios del catálogo en los selectores.

SALIDA distingue volumen y panorama **del patch** de MASTER en dB, que es la ganancia general de la app. C4 permite mantener una nota de prueba. Las ediciones siguen siendo temporales y no se incorpora guardar patches ni un editor completo de todos los parámetros. Los rótulos ficticios de la maqueta (como un cutoff de 1024) se reemplazan por parámetros y rangos válidos.

El diseño usa Canvas y widgets nativos sin imágenes pesadas, fuentes externas ni animación continua. Se redibuja al cambiar un valor, no con un temporizador de animación. En pantallas con poca altura hay desplazamiento vertical en lugar de reducir indefinidamente los controles. Las dos orientaciones horizontales siguen habilitadas.

## Audio y datos conservados

Motor interno a 40000 Hz; 48000 Hz usa conversión continua 6/5. Nativa pide 40000 Hz a Android sin garantizar que sea la frecuencia física. Se mantienen sustain, modulación, pitch bend, PANIC y volumen de -24 a 0 dB. Al salir se detiene el audio; al volver, pulsar INICIAR. La pantalla permanece encendida.

Se conservan el reparto entre MIDI y control, la última selección pendiente vigente y confirmación por respuesta real del firmware, no por caché del editor. La duración de confirmación no es latencia audible. Una transferencia grande de waves/tablas aún puede tardar.

CONFIGURACIÓN → CARGAR ROM BIN / ZIP acepta una ROM completa de 262144 bytes o ZIP con dos mitades de 128 KiB. Un update MIDI solo no alcanza. BANCOS → IMPORTAR .SYX / .MID importa Singles y sus waves/wavetables. Los ZIP de bancos requieren descomprimirse. A/B contiene 128 posiciones por banco; las importaciones reemplazan direcciones coincidentes. ROM/bancos quedan guardados internamente. No se extrae biblioteca de fábrica de la ROM.

CONFIGURACIÓN conserva COPIAR DIAGNÓSTICO: últimos 32 grupos de underruns, intervalos observados, buffers, cola aproximada, pico de render y CPU. Monitor nominal de 500 ms, fuera del hilo de render. Render mide tiempo por bloque, CPU app suma hilos (100% equivale a un núcleo), OV/s cuenta bloques tardíos y underruns informa vaciados de AudioTrack. Esta revisión visual no promete menor CPU ni eliminar underruns.

## Verificación

Las pruebas de interfaz del APK debug se ejecutan sin ROM/audio en Android 35 x86_64, con valores de ensayo identificados en las capturas; esos fixtures están sólo en androidTest. No son una prueba del rendimiento ni del MIDI USB físico de la tablet. Los metadatos de 91 parámetros se contrastan automáticamente con el catálogo upstream. Se prueban rangos, valores bipolares, ambas familias de filtros/LFO, matriz, gestos, lectura sin eco de comandos y controles deshabilitados. Se mantienen las pruebas de parsers, SRC, dispatch, ciclo de vida y diagnóstico.

La compilación release es ARM64, no depurable, paquete V2 y certificado permanente. No se incluyen firmware ni bancos protegidos. Código y modificaciones bajo las licencias del repositorio.

## Compilación

Con SDK/NDK: `bash source/Android/build-rixenia-native.sh`; después, en `source/Android/RiXeniaApp`, `gradle --no-daemon :app:assembleRelease`. CI exige el secreto permanente `RIGEAR_SIGNING_KEY_BASE64_V2`, valida el certificado del APK final y la alineación de 16 KiB. No almacena claves privadas en caché. VersionCode creciente. Los artefactos contienen fuentes Android y revisiones de submódulos; repositorio y submódulos contienen las fuentes restantes.

`-PuiSmoke` se usa sólo para APKs debug sin biblioteca nativa en las pruebas de interfaz. No usar esa propiedad para generar el release; la verificación de librerías lo rechaza.

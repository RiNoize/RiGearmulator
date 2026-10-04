# RiGear 0.10 Studio — primera implementación del instrumento

Base preservada: rama `android-v0.9-reference`, commit `85d274b22c8f38deeb467f1dbed6dc89c93e65a0`.
El DSP, RationalResampler, RuntimeBridge y AudioTrack de 0.9 se conservan. Esta entrega implementa el nuevo entorno nativo Android; no afirma resolver todavía los underruns que aparecen tras varios minutos.

## Instalación y primer arranque

La aplicación mantiene `com.rinoize.rigear`. La 0.9 usaba una clave de prueba diferente en cada runner; puede ser necesario desinstalarla antes de instalar 0.10. Conservar los ZIP/MID/BIN de ROM fuera de la app. Esta primera 0.10 crea su biblioteca privada y empieza a retener su ROM.

Desde CONFIG: importar la ROM habitual, seleccionar MIDI y Conectar, revisar audio, pulsar START. Los valores iniciales son Motor 256, salida 48000, modo Baja latencia, Salida 1024, Extra DSP 256, Clock 100%, ganancia -6 dB. Los ajustes quedan recordados. Si hay cortes, aumentar salida o usar Conservador, sin cambiar varios ajustes juntos.

Al reabrir: RiGear carga la copia de ROM ya validada y recupera la biblioteca/favoritos. START es manual, para no producir sonido inesperado. Todos los botones de la navegación son páginas reales, no imágenes ni una WebView. Cambiar páginas dentro de la app no reinicia el audio. Abrir el selector de archivos, bloquear manualmente o salir de la app lo detiene; al volver se requiere START.

La firma de desarrollo se conserva en una caché de Actions para facilitar futuras actualizaciones, pero la caché puede expirar. No es una firma de producción. **Exportar un backup de la biblioteca antes de desinstalar o actualizar con otra firma**: desinstalar Android elimina los datos privados de la app.

## INICIO

- AMP: Patch Volume, Amp Attack y Amp Release.
- FILTER: Cutoff, Resonance y Env Amount para los dos filtros. El movimiento relativo conserva la diferencia entre ambos y limita el delta común a los rangos válidos. Si el patch ya usa el Cutoff Link del firmware, Cutoff cambia el primer filtro y conserva el offset nativo del segundo; no se aplica el movimiento dos veces. Mostrar la página no cambia ningún parámetro.
- COLOR: Ring Mod Level (página A, índice 38), Sub Level y Portamento.
- Arpegiador: siete valores reales **OFF, UP, DOWN, UP/DOWN, AS PLAYED, RANDOM, CHORD**. El mockup mostraba PATTERN como modo adicional, pero el firmware no lo define así. Pattern se edita en EDIT.
- Latch, Note Length (valor nativo bipolar, no una falsa fracción de nota) y Global Tempo (63–190 BPM).
- Display de patch, anterior/siguiente, estrella, Guardar y Panic. Tocar el nombre abre Biblioteca. El filtro Solo favoritos también restringe el recorrido anterior/siguiente.

Los controles se arrastran verticalmente; un toque permite introducir el valor exacto. Los controles continuos sin conversión documentada muestran su valor nativo 0–127, no Hz, ms o dB inventados.

## EDIT / FX

El catálogo se genera desde `parameterDescriptions_C.json` del propio Osirus. Incluye parámetros públicos reales de las páginas A/B, sus límites y listas de valores. Se divide en Osciladores, Filtros, Envolventes, LFO/Mod, Arpegiador, Otros y FX. Las funciones disponibles dependen de la ROM A/B/C; los dibujos de "Analog Drift", morfismo y selectores de polifonía del mockup no se presentan como funciones reales.

SYNC obtiene los edit buffers del motor. Las copias y lecturas de estado se hacen desde el ejecutor de control, no mediante I/O de biblioteca en el hilo de audio. Se utiliza la cola nativa existente para los cambios de parámetros y para cargar sonidos.

## MULTI

Implementación inicial de las 16 partes del Virus: elegir parte, cargar un Single en esa parte, canal MIDI, habilitar/deshabilitar, nivel, pan y enviar a MAIN L/R. INICIO/EDIT/FX actúan sobre la parte seleccionada; seleccionar parte no cambia automáticamente el canal del controlador externo. Se conserva una única instancia del Virus: las partes comparten su polifonía y DSP.

Guardar Multi completo copia su configuración y los Singles disponibles de las 16 partes en un conjunto SysEx. Importar un Multi aislado sin sus Singles solo conserva las referencias a bancos/programas originales; para portabilidad usar el Multi completo o el backup de biblioteca. Esta versión no implementa todavía escenas, morph, mixer con solo ni salidas físicas independientes.

## BIBLIOTECA / FAVORITOS

- Los Factory se indexan a partir de la ROM importada; no se distribuyen ROMs ni bancos ajenos dentro de la APK.
- Biblioteca propia persistente, buscador por texto y categoría, Singles/Multis, Factory, Usuario, Importados, Recientes y Setlists.
- Estrella en lista y pantalla Inicio; **Solo favoritos** y vista **Favoritos**. Son una colección virtual, no un banco que sobrescriba A/B/C. Volver a indexar la misma ROM no elimina estrellas ni duplica sus entradas.
- Importación de `.syx` o ZIP con `.syx`, validando fabricante, longitud A/B/C, bytes MIDI y checksum antes de enviar algo al motor. Los `.mid` de firmware pertenecen a CONFIG/ROM, no a esta importación de sonidos. Presets TI y formatos FXP/FXB no se admiten todavía.
- Importar agrega a la biblioteca; **no cambia el sonido actual ni sobrescribe bancos del sintetizador**. CARGAR trabaja en el edit buffer de Single/parte.
- Guardar una copia editada, copiar, renombrar (10 caracteres ASCII del Virus), mover a colección de usuario y eliminar copias de usuario con confirmación. Factory es solo lectura. Las ediciones no guardadas se advierten antes de cambiar de sonido.
- Exportar sonido `.syx`. Exportar banco toma la lista filtrada en su orden actual y requiere 1–128 Singles, direccionados a banco A al exportar. Los Multis no se mezclan dentro de un banco de Singles.
- Setlist + crea/agrega a una lista; ↑/↓ permite reordenarla. Eliminar mientras se muestra una setlist quita solo su referencia, no el sonido de la biblioteca.
- Backup `.rigearlib` incluye sonidos, favoritos y orden de setlists, con CRC y escritura atómica. Importar un backup fusiona entradas en lugar de reemplazar silenciosamente la biblioteca actual. El backup no contiene la ROM.

No se inventan nombres de autores, descripciones musicales ni cantidades de voces DSP. La categoría mostrada proviene de los bytes del patch. El número de Unison es el parámetro nativo, no un monitor de voces activas.

## Temperatura y prueba larga

Thermal Status, Headroom y sensor CPU si Android autoriza su lectura. Lo normal en una app sin privilegios puede ser **CPU °C N/D**. La temperatura de batería se muestra separada y etiquetada; nunca se usa como si fuera CPU. Headroom es un índice térmico: 1.0 corresponde al umbral Severe, no a "100% de margen libre" ni a grados.

El muestreo térmico corre en un hilo separado: estado/sensor cada 3 segundos, headroom y batería cada 15 segundos. CONFIG muestra tiempo de sesión y primer underrun observado por el sondeo de aproximadamente 1 segundo. Exportar CSV conserva una historia acotada de CPU, Render, OV, underruns y térmica para comparar qué pasó antes y después del corte. Una correlación temporal no prueba por sí sola causalidad térmica.

## Pruebas de esta entrega

CI ejecuta pruebas JVM de checksum/corrupción SysEx, renombre sin mutar el original, 128 patches de banco, estado Single/Multi, límites de filtros linkeados, persistencia de favoritos, reindexado, protección Factory, copiar/renombrar, orden de setlist, borrado y backup. Mantiene las pruebas anteriores del SRC con ASan/UBSan y de MIDI/cola/OV; compila todo Java contra SDK 35 y C++ ARM64 Release/NDEBUG. Verifica el launcher StudioActivity y el catálogo empaquetado.

**Esas pruebas no ejecutan la ROM ni el teclado en una tablet.** Verificar físicamente: Inicio y filtros, los siete modos de arp, cambio Single/Multi, niveles/canales, guardar-cerrar-reabrir, importar/exportar, estrellas tras reinicio y sesión sostenida de 10–15 minutos. Comparar audio con la APK 0.9 preservada. La ergonomía final y las funciones avanzadas del mockup quedan para iteraciones siguientes sobre esta implementación funcional.

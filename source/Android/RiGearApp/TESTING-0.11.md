# RiGear 0.11 — Patch Browser directo

Esta versión corrige la navegación de patches de 0.10. La Biblioteca deja de comportarse como un gestor de archivos y pasa a funcionar como el browser de un sintetizador.

## Cambio principal

- Arriba: **BANK - / PATCH - / nombre / PATCH + / BANK +**.
- Biblioteca: botones **Bank A … Bank H** visibles.
- Tocar una fila de patch **lo carga inmediatamente**. El botón **CARGAR / TOCAR** hace lo mismo y queda como alternativa.
- En Factory, RiGear ya no depende de cargar un dump SysEx al edit buffer: usa el mismo mecanismo del Virus que el cambio externo que ya funciona, **Bank Select LSB (CC32) + Program Change** en Single.
- En Multi, Bank/Program se dirigen directamente a la parte seleccionada mediante los parámetros Multi del Virus, sin depender del canal MIDI actual de esa parte.
- PATCH +/- recorre el banco Factory actual. BANK +/- intenta conservar el mismo número de programa al pasar al banco siguiente/anterior.
- Favoritos, User, Importados y Setlists siguen usando la biblioteca persistente; en esas listas PATCH +/- recorre la colección seleccionada.

## Prueba recomendada

1. CONFIG: cargar/recuperar ROM, conectar MIDI y START.
2. SINGLE.
3. BIBLIOTECA → **Bank A**.
4. Tocar A001, A002, A003… y comprobar que cada fila cambia el sonido sin usar el teclado para enviar Program Change.
5. Probar **PATCH +/-** y luego **BANK +/-** desde cualquier página.
6. Marcar algunos favoritos y probar **Solo favoritos**.
7. En MULTI, seleccionar una parte y cargar un Factory en esa parte; comprobar que no cambia las otras partes.

La base de audio, SRC 46875→48000, LOW_LATENCY, biblioteca persistente y diagnóstico térmico siguen siendo los de 0.10/0.9. Esta versión no declara resuelto el underrun de sesiones largas; el cambio está concentrado en selección y navegación de sonidos.

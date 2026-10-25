# RiGear 0.12 — Neon Hardware UI

Esta versión integra la dirección visual negro/gris y azul elegida para Osirus, manteniendo el motor, MIDI, biblioteca y firma permanente de 0.11.2.

## Cambios visibles

- Display LCD azul grande y centrado con banco, número, nombre, modo y osciloscopio de la salida real.
- BANK− / PATCH− a la izquierda y PATCH+ / BANK+ a la derecha del display.
- Controles metálicos grandes con arco azul, marcas y valor numérico.
- Barra de navegación y paneles en grafito/negro, con azul para el control activo.
- Medidores CPU, Render, OV, Underruns y térmica trasladados a una barra inferior.
- EDIT con pestañas OSC/MIX, FILTER, ENV, MOD, ARP y OTROS.
- FX dividido en cuatro paneles principales: Chorus/Phaser, Delay, Reverb y Distorsión/Color.
- BIBLIOTECA conserva búsqueda de texto y muestra de forma más visible el filtro por categoría, además de Factory, Usuario, Importados, Favoritos, Recientes, Singles, Multis, bancos y setlists.

## Osciloscopio

`ScopeBus` toma una copia reducida de la señal estéreo final sin asignaciones de memoria ni bloqueos. Se captura aproximadamente 20–25 veces por segundo y la pantalla se dibuja a una frecuencia moderada. No cambia las muestras que se envían a AudioTrack. Cuando el audio está detenido aparece WAVE HOLD.

## Prueba recomendada

1. Instalar encima de la baseline 0.11.2 firmada con `RIGEAR_SIGNING_KEY_BASE64`.
2. Abrir CONFIG, iniciar audio y conectar MIDI.
3. Cargar un patch sostenido y comprobar que la forma de onda responde a las notas y al release.
4. Recorrer INICIO, EDIT, FX, MULTI y BIBLIOTECA sin que el audio se reinicie.
5. En Biblioteca, combinar texto + categoría + favoritos y cargar distintos patches.
6. Probar un patch pesado durante 5–10 minutos y comparar Render, OV y Underruns con 0.11.2.

El dibujo de la onda es diagnóstico/visual, no un osciloscopio calibrado ni una medición de latencia. La categoría de cada patch continúa viniendo de sus propios bytes Virus A/B/C.

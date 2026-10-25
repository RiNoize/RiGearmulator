# RiGear 0.12 — Neon Hardware UI

Esta versión integra la dirección visual negro/gris y azul elegida para Osirus, manteniendo el motor, MIDI, biblioteca, giro horizontal y diagnóstico de audio.

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

## Firma V2 y primera instalación

La clave inicial de pruebas quedó expuesta en el historial del repositorio y fue retirada. RiGear 0.12 inicia una identidad de firma V2 nueva, privada y explícita:

- Secreto requerido: `RIGEAR_SIGNING_KEY_BASE64_V2`.
- Certificado SHA-256 esperado: `30393306ce2796ea0effb1f199c0916c0cf7d2f2e88507c5a329e2f0f3e32775`.
- El workflow falla si el keystore o el APK no coinciden exactamente con ese certificado.

Como las APK anteriores tienen otro certificado, Android exige exportar Biblioteca > Backup, conservar la ROM, desinstalar la versión anterior e instalar una sola vez `RiGear-0.12.0-NeonUI-V2Baseline-arm64.apk`. Las versiones posteriores firmadas con V2 podrán instalarse encima.

## Prueba recomendada

1. Exportar la biblioteca y conservar la ROM antes de retirar la versión anterior.
2. Instalar la APK V2 Baseline y volver a importar ROM y backup.
3. Abrir CONFIG, iniciar audio y conectar MIDI.
4. Cargar un patch sostenido y comprobar que la forma de onda responde a las notas y al release.
5. Recorrer INICIO, EDIT, FX, MULTI y BIBLIOTECA sin que el audio se reinicie.
6. En Biblioteca, combinar texto + categoría + favoritos y cargar distintos patches.
7. Probar un patch pesado durante 5–10 minutos y comparar Render, OV y Underruns.

El dibujo de la onda es diagnóstico/visual, no un osciloscopio calibrado ni una medición de latencia. La categoría de cada patch continúa viniendo de sus propios bytes Virus A/B/C.

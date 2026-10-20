# Brilla Trivia 1.0.1 — Android

Aplicación independiente en Kotlin, paquete `ar.com.brilla.trivia`, para presentar partidas desde el teléfono mediante accesibilidad. No utiliza código, claves ni archivos de Ayelén TAP. No tiene Internet, anuncios, analítica, cuentas propias ni APIs de WhatsApp/TikTok.

## Estado de esta entrega

Primera versión para pruebas controladas. El modo de simulación y los adaptadores de accesibilidad comparten el motor de preguntas, tiempos y puntuación. **La integración con WhatsApp y TikTok no está verificada en esas aplicaciones ni en un teléfono físico.** La app pausa si no puede reconocer con seguridad el chat, sus participantes o el envío. Consultá `PRUEBAS.md` para los resultados concretos.

El mantenimiento del entorno eliminó los archivos del desarrollo anterior antes de que se entregaran. Se conservaba un registro técnico de arquitectura y correcciones, pero no su código ni su APK ni su clave. Esta versión reconstruye esos módulos; no es una recuperación idéntica de los bytes perdidos y no atribuye a esta compilación los resultados anteriores.

## Problemas conocidos (auditoría técnica 2026-10-20)

Estos problemas fueron identificados en el código fuente actual. **Ninguno se considera solucionado hasta que se verifique en dispositivos reales.**

### WhatsApp
- La aplicación reconoce y permite confirmar el grupo correctamente.
- Al intentar iniciar la partida aparece el mensaje: «No se puede verificar la lectura de participantes. Pedí un mensaje de prueba en el grupo».
- El bloqueo ocurre aunque el chat esté vacío o solo contenga mensajes propios.
- Causa en código: en `AppRuntime.start()` se exige al menos un mensaje entrante con remitente identificable y fiable antes de permitir el inicio. Esto contradice el requisito de poder arrancar sin mensajes previos.
- Archivo: `app/src/main/java/ar/com/brilla/trivia/AppRuntime.kt` (función `start`).

### TikTok
- No detecta el campo de escritura ni el título de la conversación.
- Mensaje mostrado: «No se detectó el campo o el título del chat».
- Reproducido en Samsung y Motorola, con el campo visible y el teclado abierto.
- Causas principales en código:
  1. `Adapter.fields()` descarta cualquier nodo editable cuyo `viewIdResourceName` esté vacío (`it.id.isNotBlank()`).
  2. `Adapter.titles()` solo acepta una lista fija y corta de resource-ids (`chat_title`, `conversation_title`, `toolbar_title`).
  3. La detección en `AppRuntime.detect()` falla si no hay exactamente un campo con id y al menos un título reconocido.
- Archivos: `app/src/main/java/ar/com/brilla/trivia/access/ChatAdapters.kt` y `AppRuntime.kt` (función `detect`).

### Notas
- El modo Simulación y el motor de trivia (puntuación, plantillas, tiempos) no presentan estos bloqueos.
- No se han aplicado correcciones en este commit. El repositorio conserva el código original como punto de partida.
- Para más detalle técnico ver el informe de auditoría generado por separado.

---

## Instalación y primera prueba

1. Descargá `Brilla-Trivia.apk` en el teléfono, abrilo y permití la instalación desde esa aplicación cuando Android lo solicite. Es una APK firmada para pruebas. Requiere Android 8 o superior; se compila para Android 14.
2. Abrí Brilla Trivia. Primero elegí **Simulación**. En Configuración, poné una ronda, orden secuencial y 10 segundos. Guardá.
3. En Simulación, iniciá. Esperá que aparezca la pregunta sobre Francia y que el estado diga **Esperando respuestas**. Introducí `Pedro: Londres`, `Pedro: París` y `María: París`, una línea por mensaje. Pedro y María deben sumar un punto cada uno. Esperá el anuncio final.
4. Probá los botones de cambio de grupo, pérdida del campo, retorno al grupo, pausa y Stop. Al volver, siempre hay que reanudar manualmente.
5. Para probar un chat real, permití las notificaciones y conectá **Brilla Trivia** desde Ajustes → Accesibilidad → Aplicaciones instaladas. Si Android muestra «Ajuste restringido» para una APK instalada externamente, revisá la información de la aplicación y la opción del sistema «Permitir ajustes restringidos», si está disponible. No hay que usar root ni desactivar protecciones generales.
6. Elegí WhatsApp o TikTok y abrí el panel flotante. Entrá al grupo elegido, tocá su campo de escritura y dejalo vacío. Pedí un mensaje de prueba: debe aparecer un participante identificable.
7. En el panel, tocá **Detectar y confirmar chat**, revisá el nombre y confirmalo. No uses grupos con nombres idénticos para esta prueba. Tocá ▶. Todos los mensajes se publican en nombre de tu cuenta.
8. Si aparece un error, no sigas tocando Iniciar repetidamente. Leé Diagnóstico. Ante un envío incierto, comprobá visualmente si se publicó y usá la confirmación explícita, o detené la partida. No se reenvían mensajes automáticamente.

El cronómetro empieza al confirmarse en pantalla la publicación completa de la pregunta. La confirmación exige campo vacío y un mensaje propio nuevo reconocible. Esto confirma la UI local, no entrega al servidor ni lectura por los demás.

## Banco y partidas

Formato de importación (agrega sin borrar lo anterior):

```text
A= ¿Cuál es la capital de Francia?
B= París || Paris

A= ¿Cuántos días tiene una semana?
B= 7
```

Incluye las seis preguntas de ejemplo solicitadas. Detecta duplicados por pregunta normalizada y reporta líneas inválidas. Permite editar, eliminar, restaurar ejemplos y vaciar con confirmación. Acepta importaciones de 500 o más preguntas dentro de un bloque máximo de 2 MB de caracteres. El archivo de preguntas de Ares no se incorporó todavía, según lo solicitado.

Configuración inicial: 10 rondas, 10 segundos, 5 segundos entre rondas, un punto, orden aleatorio, varios acertantes, clasificación por ronda. Cantidad personalizable de 1 a 1000, accesos 10/20/30/50. Repite por ciclos sin repetir antes de agotar el banco. Puede detenerse al agotarlo si se desactiva repetición. Clasificación top 3/5/10 o completa; empates comparten puesto y todos los primeros son campeones. Sin aciertos no inventa ganador.

Una respuesta incorrecta no elimina. Cada participante puntúa una sola vez por ronda. Comparación exacta normalizada, sin búsqueda de palabras dentro de frases; tildes opcionales y alternativas explícitas separadas por `||`. No se eliminan signos de puntuación: `París!` no coincide con `París` salvo alternativa explícita.

## Anuncios

Se guardan las 17 plantillas solicitadas y tres estilos de líneas para clasificación, acertantes y campeones. Se puede editar texto, emojis, variables, activación y orden dentro de cada fase, previsualizar y restaurar. Los anuncios largos se dividen preferentemente por espacios/saltos, respetando puntos de código Unicode. La app no garantiza que las plataformas admitan un tamaño concreto: el máximo configurable no sustituye su límite real.

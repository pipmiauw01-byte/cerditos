# 🐷 Cerditos — ayudante para el juego de sacar cerditos

App Android (11 o superior) que mira la pantalla del juego, detecta cada cerdito y hacia dónde mira,
y calcula en qué orden tocarlos para que salgan todos sin chocar. Puede mostrar el orden con números
o tocarlos por ti.

## Cómo obtener el APK

### Opción A — GitHub (sin instalar nada)
1. Crea un repositorio nuevo en github.com y sube **todo el contenido** de esta carpeta
   (incluida la carpeta oculta `.github`). Desde la web: *Add file → Upload files* y arrastra todo.
2. Entra a la pestaña **Actions**: se ejecuta solo «Construir APK» (~4 min).
3. Abre la ejecución terminada y descarga **Cerditos-apk** (un .zip con `app-debug.apk`).
4. Pasa el APK al teléfono e instálalo (acepta «instalar apps desconocidas»).

### Opción B — Android Studio
Abre esta carpeta con *File → Open*, espera a que sincronice y usa *Build → Build APK(s)*.

## Activarlo en el teléfono
1. Abre **Cerditos** → «Activar el ayudante» → *Aplicaciones instaladas* → **Cerditos – ayudante** → activar.
2. Si sale «Ajuste restringido» (pasa con apps instaladas fuera de Play Store): en la app toca el botón café,
   luego **⋮ → Permitir ajustes restringidos**, y repite el paso 1.

## Uso
Abre el juego **al comienzo de un nivel**. Aparece un panel flotante 🐷:

| Botón | Qué hace |
|---|---|
| Analizar | Pone 1, 2, 3… sobre los cerditos. El naranjo es el siguiente. |
| Siguiente | Toca por ti el próximo cerdito y vuelve a analizar. |
| Auto ▶ | Saca todos solo, uno por uno. «Parar ■» lo detiene. |
| Borrar | Quita los números. |

Si un cerdito no tiene salida aunque saques todos los demás, aparece con ✕ rojo, y uno con **OVNI** amarillo:
es el que conviene quitar con el booster del OVNI para liberar a la mayor cantidad.

## Cómo funciona
- `Detector.kt`: separa lo rosado del pasto verde, agrupa cada cerdito, saca su eje largo y busca los ojos
  para saber cuál extremo es la cabeza.
- `Solver.kt`: cada cerdito ocupa 2×1 casillas de una grilla; corre recto hacia donde mira. Siempre saca
  uno con el camino libre. Como sacar un cerdito nunca tapa a otro, si existe una forma de sacarlos a todos,
  así se encuentra.
- `PigService.kt`: servicio de accesibilidad que captura la pantalla, dibuja encima y hace los toques.

## Ajustes si algo falla
- **No detecta cerditos / detecta cosas raras**: el filtro de color está en `Detector.kt` (`r > g + 8 …`).
- **El auto toca demasiado rápido**: sube `STEP_DELAY_MS` en `PigService.kt` (por defecto 1300 ms).

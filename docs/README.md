# Plan del proyecto — AI Financial Twin (LauzHack 2026)

> Documento de contexto para los tres desarrolladores y sus agentes (Claude Code, etc.).
> Resume las decisiones tomadas hasta ahora. Si algo cambia, actualizad este fichero.

## 1. Qué construimos

Un juego tipo **"diseña tu propia vida"** que ayuda al usuario a visualizar y comparar escenarios financieros.
Integra dos retos a la vez:

- **AI Financial Twin** (reto principal, autora del concepto: Stefania Stan). Principio: *"Don't predict the future. Make it explorable."*
  Hay que mostrar: estado financiero ficticio, una intervención/decisión, comparación base vs. alternativa, supuestos transparentes,
  limitaciones y explicación de por qué difieren los resultados.
- **JetBrains Kotlin Multiplatform**. La app debe correr en **al menos 2 plataformas** y se puntúa cuánta lógica
  vive en `commonMain`, además de modo oscuro/claro, localización, Compose Multiplatform, backend Kotlin y lógica de IA compartida (Koog).
  Puntos extra por *Agentic Development* con JetBrains Air (documentad qué hizo cada agente y qué corregimos).

## 2. Decisiones tomadas

| Tema | Decisión |
|---|---|
| Stack | Kotlin Multiplatform + Compose Multiplatform |
| Plataformas | Android + Desktop (JVM) |
| Idiomas | ES / EN |
| Tiempo | Menos de 24 h → MVP muy recortado |
| Perfil | El usuario introduce el suyo mediante entrevista de opción múltiple. **Alexia** (20 años, estudiante, Lausanne, CHF 8.000) queda precargada como perfil de demo por si falla |
| Duración de la partida | ~5-6 turnos sobre un horizonte de ~10 años |
| LLM | **Sin decidir**. Se oculta tras la interfaz `FutureSelfChat` para poder cambiarlo sin tocar nada más |

## 3. Bucle de juego

Basado en el diagrama del equipo: entrevista → modelado del perfil → eventos (con bucle) → chat con el "yo del futuro" + estadísticas.

```
Turno N (un evento de vida)
 ├─ Decisión A ──► resultado con 3 casos (pesimista / neutro / optimista)
 └─ Decisión B ──► resultado con 3 casos (pesimista / neutro / optimista)
        │
   el usuario elige A o B
        │
   se resuelve UN caso → el estado financiero se actualiza → Turno N+1
```

- Cada evento ofrece **dos decisiones**; cada decisión produce un resultado con **tres casos**.
- Antes de elegir se muestran los 3 casos de A y de B para comparar.
- Al elegir, se **sortea un caso con probabilidades** (por defecto 20 % pesimista / 60 % neutro / 20 % optimista, ajustables por perfil/evento), con semilla para reproducibilidad.
- Opcional: modo "elige tú el caso" para exploración libre. *(Pendiente de confirmar si se implementa.)*
- Al final se compara el camino elegido con el **camino alternativo** (qué habría pasado con la otra decisión).
- Los eventos que aparecen dependen del perfil (p. ej. estudiante → máster/beca; jubilado → salud/herencia) con condiciones simples (`requires`).

Terminología: usamos **caso** (pesimista/neutro/optimista), no "entorno".

## 4. Arquitectura

Casi todo en `commonMain`:

```
commonMain
├─ model/     Profile, FinancialState, Event, EventOption, Case, Outcome
├─ engine/    simulación mensual determinista, aplicación de eventos, sorteo de caso, contrafactual
├─ events/    catálogo de eventos y selección según perfil
├─ data/      carga de perfiles/escenarios (JSON embebido)
├─ ai/        interfaz FutureSelfChat (implementación LLM intercambiable)
└─ ui/        pantallas Compose: entrevista, dilema, comparador, chat, resultados
androidMain / desktopMain  → solo arranque, ficheros y clave API
```

Motor = función pura: `apply(state, option, case) → newState + explicación`. Fácil de testear.

### Formato de evento (datos, no código)

```json
{
  "id": "E-master",
  "title": "Te ofrecen entrar a un máster",
  "requires": { "employment_status": "student" },
  "options": [
    { "id": "A", "label": "Hacer el máster",
      "cases": {
        "pessimistic": { "prob": 0.2, "effects": [], "narrative": "..." },
        "neutral":     { "prob": 0.6, "effects": [], "narrative": "..." },
        "optimistic":  { "prob": 0.2, "effects": [], "narrative": "..." } } },
    { "id": "B", "label": "Ponerte a trabajar", "cases": { } }
  ]
}
```

Cada `effect` es una operación numérica sobre el estado: `income_delta`, `expense_delta`, `one_off_cost`,
`asset_return`, `debt_add`, con duración en meses.

## 5. Datos (`ai-financial-twin-lauzhack-2026/Data`)

Datos **sintéticos** del reto, 1.200 perfiles, todo enlazado por `profile_id` (P00001…P01200):

| Archivo | Contenido |
|---|---|
| `synthetic-profiles.csv` | Perfiles: edad, ciudad, cantón, empleo, hogar, caja inicial, ingreso, alquiler |
| `synthetic-accounts.csv` | Cuentas y saldo de apertura (2025-01-01) |
| `synthetic-assets.csv` | Efectivo, inversiones, etc. |
| `synthetic-cash-balances.csv` | Serie mensual de caja por perfil (24 meses) |
| `synthetic-liabilities.csv` | Deudas (solo instantánea inicial, **no amortizadas**) |
| `synthetic-goals.csv` | Metas con importe, fecha y prioridad |
| `scenario-catalog.csv` | 120 escenarios (inspiración, no hay que implementarlos todos) |
| `scenario-assumptions.csv` | Parámetros ilustrativos (matrícula, duración…) |

**No hace falta generar datos nuevos**; se usan para calibrar:
1. La entrevista rellena los campos de `synthetic-profiles.csv`.
2. Se busca el perfil sintético más parecido (vecino más cercano) para inferir gasto típico, activos y deudas.
3. El catálogo de eventos sale de `scenario-catalog.csv` + `scenario-assumptions.csv`.

Carencias conocidas: el README del dataset menciona `synthetic-transactions.csv`, `life-events.csv` y `alexia-profile.json`
que **no existen** (solo hay saldos mensuales agregados). Las deudas no se amortizan en el dataset: lo hace el motor.
Las cifras (matrículas, etc.) no están verificadas: documentadlas como supuestos.

Los CSV no se pueden leer con `java.io` en `commonMain`: convertidlos a JSON pequeño (p. ej. ~50 perfiles) con un script y metedlos en los resources.

## 6. Estadísticas

**MVP (imprescindibles)**
1. Evolución de la caja en el tiempo, con los 3 casos como abanico.
2. Patrimonio neto (caja + activos − deudas).
3. Liquidez mínima y meses en negativo.
4. Metas alcanzadas y fecha estimada.
5. Camino elegido vs. camino alternativo.

**Si da tiempo:** tasa de ahorro, ratio de endeudamiento, fondo de emergencia (meses cubiertos), riesgo (probabilidad de quedarse sin caja con todo pesimista), comparación con perfiles similares, puntuación final / "perfil de decisor".

Monte Carlo queda **fuera del MVP**: los 3 casos con probabilidad ya expresan la incertidumbre.

## 7. Reparto del trabajo

| Dev | Responsable de | Entregables |
|---|---|---|
| **A – Motor** | Simulación y estadísticas | Estado financiero, `apply(state, option, case)`, sorteo de caso con semilla, contrafactual, tests de coherencia contable |
| **B – UI/UX** | Compose y pulido | Entrevista, pantalla de dilema con 2 cartas y vista de los 3 casos, animación de resolución, gráficos, pantalla final, tema claro/oscuro, i18n ES/EN, empaquetado Android + Desktop |
| **C – Datos + IA** | Calibración, eventos y chat | Subconjunto de datos en JSON, calibración, catálogo de eventos, chat del "yo del futuro" con el historial de decisiones como contexto |

**Primeras 1-2 h, juntos:** acordar los `data class` e interfaces (`FinancialState`, `Event`, `apply(...)`, `FutureSelfChat`)
y trabajar cada uno contra mocks. Escribir el contenido (6-8 eventos × 2 opciones × 3 casos ≈ 40-50 entradas) es la tarea más pesada: repartidla desde el principio.

**Cronograma (24 h)**
- 0-2 h: contratos y esqueleto KMP (compila en Android y Desktop)
- 2-10 h: motor, UI base y datos en paralelo
- 10-16 h: integración del bucle de juego completo
- 16-21 h: chat con el LLM, estadísticas, pulido
- 21-24 h: demo, README, vídeo. **Congelar funcionalidades a partir de la hora 18.**

## 8. Cosas importantes

- **El LLM no inventa números**: recibe los resultados del motor como contexto y solo los explica/narra.
- **Clave del LLM**: nunca en el repo ni embebida en la app. Variable de entorno en Desktop; proxy mínimo (Ktor) si se quiere en Android.
- **Plan B de demo**: chat con respuestas de ejemplo por si falla la red; perfil Alexia precargado.
- **Gráficos**: pocas librerías en KMP; usar `Canvas` o Koalaplot (decidirlo pronto).
- **Responsabilidad**: mostrar supuestos, unidades y limitaciones; avisar de que no es asesoramiento financiero ni una predicción.
- **Licencia**: los materiales del reto son "all rights reserved" (autora: Stefania Stan). Usarlos solo para participar y citar la fuente.
- **Pendiente**: leer `evaluation/judge-scorecard.md` (rúbrica propuesta).
- **Preguntas abiertas**: elección del LLM; si se implementa el modo "elige tú el caso".

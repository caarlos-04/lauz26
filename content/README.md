# content/ — contenido del juego

| Fichero | Qué es | Contrato |
|---|---|---|
| `events.json` | Catálogo de eventos (dilemas con 2 opciones × 3 casos) | `docs/README.md` 5.3 |
| `facts.json` | Hechos de vida (estudios, ocupación, ciudad, vivienda, familia), hitos y reglas de hechos iniciales | `docs/README.md` 5.4 |
| `assumptions.json` | *(pendiente)* inflación, crecimiento salarial, financiación, penalizaciones, rangos de sensibilidad | `docs/README.md` 5.7 |

Cuando exista el proyecto KMP, estos ficheros se copian a los recursos de `commonMain`.

## Antes de subir contenido
```
python tools/calibrate.py                                       # una vez: genera tools/out/
python tools/validate_events.py --preview P00001                # cada opción x caso sobre Alexia
python tools/validate_events.py --play P00001 --choices A,B     # partida completa: línea de vida
```

## La idea: historias, no preguntas sueltas
El objetivo es que dos partidas sean **dos vidas distintas**. Para eso los eventos se encadenan:
- **Por hechos**: `requires.facts` (p. ej. solo quien tiene `EDUCATION: MASTER`).
- **Por hitos**: `requires.flagsAll` / `flagsNone` (p. ej. solo si `took_loan`, o nunca si ya `did_master`).
- **Por caja**: `requires.minCashChf` / `maxCashChf` (p. ej. `E-financing` solo con caja negativa).
- **Por prioridad**: los eventos urgentes (`priority` alta) se cuelan antes que los demás.
- Si ningún evento encaja, el turno es tranquilo: **diseñad eventos para cada etapa** o la historia se queda vacía.

## Reglas para escribir un evento
1. **Un dilema real.** Las dos opciones deben tener ventajas distintas. Usa `--preview` para comprobar que ninguna gana en todo.
2. **Cada cifra con fuente** en `sources`. Si sale del dataset, cita el fichero; si no, escribe que es un supuesto.
3. **La vida también cambia**: cada caso debería tocar algún hecho o hito (`set_fact`, `add_flag`, `set_employment`), no solo dinero.
4. **Probabilidades por defecto** 0,2 / 0,6 / 0,2. Usa 0,25 / 0,5 / 0,25 en opciones más inciertas.
5. **Importes absolutos en CHF mensuales**, pensados para el perfil al que va dirigido el evento.
6. **Desfases (`startOffsetMonths`)** relativos al inicio del turno. Un turno dura 24 meses.
7. **Narrativa** en segunda persona, una o dos frases, con las cifras clave del caso. Es lo que se lee durante la partida.
8. Hechos e hitos nuevos: **declararlos primero en `facts.json`** (con ES y EN) o el validador los rechaza.
9. Los `streamId` que reemplazas deben existir: `income` (o `pension` en jubilados).

## Tipos de efecto
| type | Para qué |
|---|---|
| `set_income` | Cambiar un ingreso de forma permanente (nuevo trabajo) |
| `income_delta` | Ingreso extra o recorte, temporal o permanente (beca, reducción de jornada) |
| `expense_delta` | Gasto extra o ahorro, temporal o permanente (matrícula, guardería) |
| `one_off` | Gasto o ingreso puntual (mudanza, ayuda familiar: negativo = ingreso) |
| `add_liability` | Préstamo nuevo, con cuota calculada por plazo |
| `asset_return_delta` | Cambiar la rentabilidad de un tipo de activo |
| `set_employment` | Cambiar la situación laboral (afecta a la paga extra y a qué eventos salen) |
| `set_fact` | Cambiar un hecho de vida (estudios, ocupación, ciudad, vivienda, familia) |
| `add_flag` | Añadir un hito a la historia |

## Estado actual
| Evento | Para quién | Notas (`--preview`/`--play`, Alexia, 10 años, sin inflación) |
|---|---|---|
| `E-master` | Estudiante de grado ≤ 30 | Máster: patrimonio esperado ≈ 336k, pero 23-41 meses en negativo. Trabajar: ≈ 329k, nunca en negativo |
| `E-financing` | Caja negativa al inicio del turno (prioridad 10) | Préstamo (tipos del dataset 2,5-8,5 %) o ayuda familiar (5-30k, supuesto) |

Alexia sin ningún evento pierde ≈ 1.000 CHF/mes y paga intereses por el descubierto (−170k a 10 años).
**Falta**: eventos para los turnos 2-4 (primer empleo, vivienda, pareja, mudanza, inversión...), o la partida
termina con turnos tranquilos.

# AGENTS.md

Instrucciones para los agentes de IA (Claude Code, etc.) que trabajan en este repositorio.

## Contexto
Lee `docs/README.md` antes de empezar: contiene el plan, la arquitectura, el reparto de trabajo y las decisiones tomadas.

## Git
- **No hagas commits sin preguntar** al usuario. Muestra antes qué vas a incluir.
- **No añadas `Co-Authored-By`** ni ninguna línea de atribución a la IA en los commits o en las PRs.
- **Nunca uses `git push --force`** ni `--force-with-lease` sin permiso explícito.
- No hagas `push` sin preguntar.
- No reescribas el historial (`rebase`, `commit --amend`, `reset --hard`) de commits ya publicados.
- No trabajes directamente en `main`: usa una rama propia y pregunta antes de fusionar.
- Haz commits pequeños y con mensajes claros.
- No borres ramas ni archivos de otros compañeros sin preguntar.

## Coordinación entre desarrolladores (GitHub issues)

Usa `gh` para todo lo de esta sección. Repo: `caarlos-04/lauz26`.

### Quién es quién
| Rol | Usuario de GitHub | Etiqueta | Responsabilidad (detalle en `docs/README.md`, sección 11) |
|---|---|---|---|
| Dev A – Motor + integración | `polma7` | `polma7` | Esqueleto KMP, `model/`, motor, árbol, atribución, tests |
| Dev B – UI/UX (front) | `OriolVines` | `OriolVines` | Pantallas, línea de vida, gráficos, i18n, empaquetado |
| Dev C – Datos + contenido + IA | `caarlos-04` | `caarlos-04` | Calibración, eventos, hechos, supuestos, crónica y chat |

Cada persona tiene una etiqueta con su usuario de GitHub. Al empezar una sesión, averigua para quién trabajas con `gh api user --jq .login` y usa su rol y su etiqueta.

### Cuando necesites algo de otro desarrollador: crea una issue
Si te falta algo que es responsabilidad de otro (un cambio en `model/`, una función del motor, una pantalla, un fichero de contenido...), **no lo hagas tú en su módulo**: crea una issue y etiquétale.
1. Busca antes si ya existe: `gh issue list --state open --search "<palabras clave>"`.
2. Créala asignada a esa persona, con su etiqueta y mencionándola en el cuerpo:
   ```
   gh issue create --title "[caarlos-04 → polma7] <qué se necesita>" \
     --assignee <usuario> --label <usuario> \
     --body "@<usuario> <cuerpo con la plantilla de abajo>"
   ```
3. Cuerpo de la issue:
   - **Qué necesito**: una frase.
   - **Para qué**: qué bloquea (fichero, pantalla, paso del flujo P1-P12).
   - **Contrato**: dataclass, función o fichero JSON afectado, con enlace a la sección de `docs/README.md`.
   - **Cuándo**: hora del cronograma en que lo necesitas, y si te bloquea ya.
   - **Quién lo pide**: tu rol y tu rama.
4. Si es un **cambio de contrato** (`model/`, formatos JSON), añade la etiqueta `contrato` y menciona a todos los afectados.
5. Dile al usuario qué issue has creado (número y enlace).

### Cada vez que hagas push: revisa si te han etiquetado
Justo después de cada `git push` (y también al empezar la sesión):
1. Busca issues abiertas que te afecten:
   ```
   gh issue list --state open --assignee @me
   gh issue list --state open --label <tu-usuario>
   gh search issues --repo caarlos-04/lauz26 --state open --mentions <tu-usuario>
   ```
2. Lee cada una con sus comentarios: `gh issue view <n> --comments`. Fíjate en los comentarios nuevos desde tu última respuesta.
3. Para cada issue, haz una de estas dos cosas:
   - **Si piden información o estado**: responde con un comentario (`gh issue comment <n> --body "..."`) indicando: estado (hecho / en curso / bloqueado), qué falta, rama y commit si aplica, y cuándo estará.
   - **Si piden código**: impleméntalo en tu rama y súbelo **siguiendo las reglas de Git de arriba** (enseña al usuario qué vas a incluir y pide confirmación antes del commit y del push). Después comenta en la issue con la rama, el commit o la PR (`Closes #<n>` en la descripción de la PR).
4. No cierres una issue de otro sin que lo confirme quien la pidió; cierra las tuyas cuando estén resueltas.
5. Resume al usuario qué issues has encontrado y qué has respondido o subido.

Comentarios e issues en español, cortos y concretos. No menciones a personas que no estén implicadas.

## Seguridad y datos
- **Nunca** subas claves de API, tokens ni credenciales. Usa variables de entorno y asegúrate de que estén en `.gitignore`.
- Usa solo datos **sintéticos**. Ningún dato personal o financiero real.
- No incluyas contenido de terceros sin comprobar su licencia.

## Trabajo en equipo
- Somos 3 desarrolladores trabajando en paralelo. Toca solo tu módulo; si necesitas cambiar un contrato compartido (modelos, interfaces), avisa antes.
- La lógica va en `commonMain` siempre que sea posible; el código específico de plataforma, solo si no hay alternativa.
- Si algo no está claro, **pregunta antes de inventar**.

## Código
- Kotlin Multiplatform + Compose Multiplatform. Android + Desktop (JVM).
- El motor financiero es una función pura y debe tener tests.
- Cifras y supuestos financieros: documenta unidades (CHF) y fuente. El LLM nunca inventa números: solo explica los que calcula el motor.
- No añadas dependencias nuevas sin comentarlo con el equipo.
- Textos de la interfaz en ES/EN, nunca cadenas fijas en el código.
- Comentarios en ingles

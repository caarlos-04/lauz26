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

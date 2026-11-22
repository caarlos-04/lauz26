# AI — chat with your future self and chronicle (P11–P12)

Code: `shared/src/commonMain/kotlin/com/lauzhack/twin/ai/`. Contract: `docs/README.md` §5.8 (`FutureSelfChat`, `ChatContext`).
Built with **Koog** 1.3.0; provider OpenAI (`gpt-4.1-mini`) or Anthropic (`claude-haiku-5-5`), canned answers otherwise.

## Architecture

```
ui/AppState  ── ChatContext(result: GameResult of the branch selected in the tree, facts, assumptions, lang, whatIfTurns)
     │
     ▼ FutureSelfChat.ask(context, persona, history, question) / chronicle(context)
AgentFutureSelfChat ── BriefingMapper.map(context) ──► LifeBriefing ── Prompts.renderBriefing() ──┐ dynamic part
     │                                                                                              ▼
     │                 Prompts.FUTURE_SELF / PARALLEL_SELF / CHRONICLE prompt (static) ────► system message
     │                 chat history (user / assistant, offline answers left out) ──────────► messages
     ▼
FutureSelfAgent (Koog AIAgent, single run, no tools) ──► OpenAI / Anthropic
     │ on any error (no network, bad key, rate limit)
     └──► CannedFutureSelfChat (plan B), marked offline in the UI
```

- **Static part** (`Prompts`): role (future self, parallel self or chronicle writer), tone, length and the rules of docs §10:
  only numbers from the briefing, copy them exactly, say "I don't remember" otherwise, cite year/age, simulation ≠ advice,
  no new scenarios, answer in the user's language.
- **Dynamic part** (`LifeBriefing` → `renderBriefing`): profile, life facts at start/end (labels from `facts.json`), money at start
  and now, every turn (question, choice, luck, outcome, net worth before/after; quiet periods), moments (`StoryBeat`s), goals,
  risk (low point, months negative / below cushion, financing cost), cash band, what moved net worth (`Attribution`), parallel
  lives, assumptions. Amounts are pre-formatted in CHF so the model copies them instead of calculating.
- Rebuilt **on every call** from the current branch: editing the tree changes what the future self knows on the next message
  (the UI also clears the chat and the story). What-if branches and edited turns are flagged.
- **Personas**: `FUTURE_SELF` (you in 10 years) and `PARALLEL_SELF` (the you who took the other first option; parallel life
  `OTHER_FIRST_CHOICE`). **Chronicle**: ≤250-word story of the ten years, generated on demand in Results.

## Configuration
| Env var | Default | Meaning |
|---|---|---|
| `OPENAI_API_KEY` | — | OpenAI API key (prepaid credits). Used first if set |
| `ANTHROPIC_API_KEY` | — | Claude API key. Used if there is no OpenAI key |
| `TWIN_LLM_MODEL` | `gpt-4.1-mini` / `claude-haiku-5-5` | Model id of the selected provider |

Setup (desktop), once per person:
```bash
cp .env.example .env        # .env is git-ignored
# edit .env: OPENAI_API_KEY=sk-...
./gradlew :desktopApp:run   # the chat badge shows "AI · gpt-4.1-mini"
```
Environment variables still work and take precedence over `.env`.

Getting keys as a team: create a Project on platform.openai.com with a monthly budget limit and invite the team; each
person creates their own key in it (shared billing, revocable one by one). If one key is shared, send it privately
(password manager / direct message), never in the repo, issues, PRs or public channels, and rotate it after the hackathon.

- Keys are **never** committed. Web reads no key and uses the canned answers.
- **Android:** DEBUG builds take `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `TWIN_LLM_MODEL` from the environment or the
  repo-root `.env` **at build time** (`androidApp/build.gradle.kts` → `BuildConfig` → `AndroidEnv` in `MainActivity`), so
  the chat talks to the real LLM on our own test phones. The key ends up inside that APK: **never share a debug APK
  built with a key** (anyone can extract it). Release builds never get a key. The proper fix is a small Kotlin backend
  that keeps the key (option discussed with the team).
- Cost: ~2–4k tokens in, ~200 out per question: a fraction of a cent with a small model.
- Privacy: the briefing contains only synthetic data; the questions the user types are sent to the provider.

## Contract notes (for Dev C)
- `FutureSelfChat` and `ChatContext` follow §5.8; `ChatContext` has one extra defaulted field, `whatIfTurns`.
- `ReportsOffline` lets the UI show when an answer came from the fallback.
- `LifeBriefing` is the only thing the prompts read: to change what the LLM knows, change `BriefingMapper`.

## Tests
`./gradlew :shared:jvmTest` — `PromptsTest` (briefing content, rules, personas, chronicle, determinism) and
`AgentFutureSelfChatTest` (mapping a played `GameResult`, what-if flag, persona prompt and history, offline fallback) with a
fake `ReplyGenerator` (no network).

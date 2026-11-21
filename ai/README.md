# ai/ — Chat with your future self (Koog + OpenAI / Claude)

Kotlin Multiplatform library (Android + Desktop) with the agent that answers as the user **ten years from
now**, using only the decisions and results of the branch selected in the decision tree.
It is built as the `:ai` module of the `ui/` Gradle build (`ui/settings.gradle.kts`).

## Architecture

```
ui/ AppState.ask()                       ai/ (this module)
  ChatContext (branch selected in tree)
        │  BriefingMapper (ui/…/ai)       LifeBriefing  ── Prompts.renderBriefing() ──┐ dynamic part
        └────────────────────────────────►                                            ▼
                                          Prompts.SYSTEM_PROMPT  ─────────────► system message
                                          chat history (user/assistant)  ─────► messages
                                          FutureSelfAgent (Koog AIAgent, single run) ──► OpenAI gpt-4.1-mini (or Claude)
```

- **Static system prompt** (`Prompts.SYSTEM_PROMPT`): role, tone and the rules from `docs/README.md` §10
  (only numbers from the context, say "I don't know" otherwise, cite age/year, simulation ≠ advice,
  no new scenarios, answer in the user's language).
- **Dynamic briefing** (`LifeBriefing` → `Prompts.renderBriefing`): profile, every decision (question, choice,
  luck, outcome, net worth before/after), goals, risk, Monte Carlo outlook, the other path and the assumptions.
  Numbers are pre-formatted in CHF so the model copies them instead of calculating.
- It is rebuilt **on every question** from the current branch, so editing the tree changes what the future self
  knows on the next message (the UI also clears the chat on edit). What-if branches are flagged.
- `LifeBriefing` is owned by this module so `ai/` does not depend on the UI model; the mapping lives in
  `ui/composeApp/src/commonMain/kotlin/ch/lauzhack/twin/ai/BriefingMapper.kt`.
- If the call fails (no key, no network, rate limit) the UI falls back to the canned answers and marks them offline.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `OPENAI_API_KEY` | — | OpenAI API key (platform.openai.com, prepaid credits). Used first if set. |
| `ANTHROPIC_API_KEY` | — | Claude API key (Claude Console). Used if there is no OpenAI key. |
| `TWIN_LLM_MODEL` | `gpt-4.1-mini` / `claude-haiku-5-5` | Model id of the selected provider. |

With no key the app uses the canned answers.

```bash
cd ui
export JAVA_HOME=/opt/idea-IU-263.7307.200/jbr     # or any JDK 17+
OPENAI_API_KEY=sk-... ./gradlew :composeApp:run
```

- Keys are **never** committed or bundled in the app. Android reads no key and uses the canned answers.
- Cost: a question sends ~2–4k tokens and gets ~200 back; with a small model (gpt-4.1-mini, Claude Haiku)
  that is a fraction of a cent per question. Check current prices on the provider's page.
- Privacy: the briefing contains only synthetic data; the questions the user types are sent to the provider (OpenAI or Anthropic).

## Tests

```bash
cd ui && ./gradlew :ai:desktopTest :composeApp:desktopTest
```

`PromptsTest` checks the briefing and the rules; `AgentFutureSelfChatTest` (in `ui/`) checks the mapping
and the offline fallback with a fake agent (no network).

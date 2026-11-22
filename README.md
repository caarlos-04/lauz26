# VTwin — explore every life you could live

**An AI financial twin you can play.** You take the decisions of the next ten years of a (fictional) person's life,
see what each one does to their money and their goals, go back to any moment to try something else, and talk to your
self at any point of that life. Every number is computed by a simulation learned from data; the AI only explains it.

Built for **LauzHack 2026**, for two challenges at once:
- **UBS × LauzHack — AI Financial Twin** (concept by Stefania Stan): *"Don't predict the future. Make it explorable."*
- **JetBrains — Cross-platform apps with Kotlin Multiplatform**: one Kotlin code base for Android, desktop and web.

> Simulation with synthetic data: it is not a prediction and not financial advice. Amounts in CHF, without taxes.

## What you can do

- **Start as Alexia** (20, student in Lausanne, CHF 8,000 saved) **or build your own profile** in a short interview.
- **Decide, five times, every two years.** At each moment you see every action open to you (do a master's, start
  working, move to a shared flat, go part-time, take a loan…; "carry on as you are" is always one of them) and what
  depends on luck, with its usual range. You do not see the balances before deciding.
- **Live the consequences.** The luck of that moment draws the uncertain numbers (salary, tuition, rent…); a shock
  you did not choose can happen (a job loss); 24 months are simulated month by month.
- **Go back and decide something else.** Every moment of every life you explored is kept. The **map of lives** shows
  them as a tree: go to any moment, decide what comes next or try another action there.
- **Choose how luck works**: the same luck at the same age in every branch (so the differences come only from your
  decisions), or a different luck for every decision, as in real life.
- **Follow your goals** (emergency savings, a deposit…): % reached, deadline and status at every moment.
- **See where every number comes from.** Any turn, any moment of the map and the whole life open into the
  calculation: start + every category of money in and out = end (checked), each figure labelled as *dataset*,
  *public data*, *decision* or *assumption*, with the decision's effects and sources.
- **Talk to your self of any moment.** After any decision you can chat with "you, right after that decision": an LLM
  that only knows the life up to that moment and only uses the numbers the engine computed. At the end, with your
  future self, ten years on. Without a key it answers with sample answers built from the same numbers.
- Light and dark mode, English and Spanish, phone and desktop layouts.

## How it works

```
 Synthetic dataset (1,200 profiles, 552,295 transactions) ─► flow model (learned rules) ─┐
 Swiss public data (BFS, SNB, World Bank, Eurostat, BIS)   ─► macro trends (wages, rents, prices, career) ─┤
                                                                                                          ▼
 Profile ─► FinancialStatement ─► decision: action + luck + shock ─► 24 × step(month) ─► next statement ─► …
                                                                                                          │
                          map of lives · goals · "where the numbers come from" · chat with your self ◄────┘
```

- **The engine is a pure function** (`step(statement of month t) = statement of month t+1`): the same inputs and seed
  always give the same life. It is written in Kotlin and mirrored in Python (`tools/`) as a reference.
- **Learned from the data, not invented.** For each kind of money that moves in a month (salary, rent, groceries,
  travel, December bonus…) a *hurdle* model learns how likely it is and how big it is, as multiplicative rules
  ("students spend 21 % less on food", "Geneva costs 29 % more than Neuchâtel"), fitted by iterative proportional
  fitting; a statistical test decides which of the person's 15 fields enter each rule. What the two-year dataset cannot
  teach (growth over ten years) comes from Swiss public statistics.
- **Uncertain numbers are distributions.** Each one is fitted through three values of the data (p25 / p50 / p75 of a
  salary, a tuition…) as a two-piece normal; the luck of a decision is the percentile they are drawn at.
- **The AI only explains.** The chat gets a briefing built from the engine's results (life up to that moment, money,
  goals, where each figure comes from, assumptions) and rules: use only those numbers, copy them exactly, say "I do not
  remember" otherwise, never give advice. Agent built with **Koog** (JetBrains), OpenAI or Anthropic models.

Details: [`docs/decision-tree.md`](docs/decision-tree.md) (the game), [`docs/how-the-simulator-learns.md`](docs/how-the-simulator-learns.md)
(the model, with real numbers), [`docs/simulator.md`](docs/simulator.md) (reference), [`docs/ai.md`](docs/ai.md) (chat).

## Run it

Requires JDK 17+ (Gradle downloads the rest). From the repository root:

| Platform | Command |
|---|---|
| Desktop | `./gradlew :desktopApp:run` |
| Web (Wasm) | `./gradlew :webApp:wasmJsBrowserDevelopmentRun` |
| Android | `./gradlew :androidApp:assembleDebug` (needs the Android SDK; APK in `androidApp/build/outputs/apk/debug/`), or `installDebug` with a phone connected |
| Tests | `./gradlew :shared:jvmTest` and `cd tools && python -m unittest` |

Then press **"Play as Alexia"**.

**Chat with a real LLM (optional).** Copy `.env.example` to `.env` (git-ignored) and set `OPENAI_API_KEY` or
`ANTHROPIC_API_KEY`. The desktop app reads it at start-up. Android **debug** builds read it at build time, so the key
ends up inside that APK: use it on your own phone and never share it. Web and release builds use the sample answers.

## How we know it works

| Check | Result |
|---|---|
| Out of sample: learn on 960 profiles, predict the other 240 for 24 months from their opening state | median error of closing cash **CHF 1,214**; the p10–p90 band holds the real value 85 % of the time |
| All 1,200 profiles × 24 months (`tools/simulate.py --validate`) | **0 accounting errors** |
| Kotlin engine vs Python reference (golden games) | largest difference **CHF 0.00** |
| Every turn and every whole life in the test tree of Alexia | start + every category = end, to the cent (also shown in the app) |
| Goals shown in the UI vs the engine (same test tree) | identical in every node |
| Kotlin test suite | **97 tests** (engine, tree, goals, traceability, prompts, chat state, interview) |

## Kotlin Multiplatform

About **98 % of the Kotlin code lives in `shared/commonMain`**: the model, the engine, the tree of lives, the chat
agent and prompts (Koog), and the whole Compose Multiplatform UI. Each platform only adds a few lines: start-up,
reading the LLM key and the locale.

```
shared/commonMain   model/ engine/ ui/ ai/ interview/ chronicle/ content/   (≈ 7,900 lines)
shared/androidMain · jvmMain · wasmJsMain                                    (≈ 120 lines)
androidApp · desktopApp · webApp                                             (≈ 50 lines: start-up)
tools/      Python: data preparation, model learning, reference engine, content validation
content/    game content as JSON (actions, facts, assumptions, interview, chronicle templates)
docs/       plan, model, simulator, decision tree, AI, UI
```

Design: an editorial "ink & cobalt" style with IBM Plex Sans and Plex Mono for figures, checked contrast in light and
dark mode ([`docs/ui.md`](docs/ui.md)).

## Data, responsibility and limits

- **Only synthetic data** from the challenge plus public aggregate statistics; no personal data. Sources and terms:
  [`data/README.md`](data/README.md) and [`content/README.md`](content/README.md).
- **Observed, learned and assumed are kept apart** and labelled in the app. Assumptions (financing rate, 0 %
  investment return, chance of a job loss…) are listed with their source in the Assumptions tab.
- **Limits, said honestly:** the dataset is synthetic, so a good fit means we recovered its generator, not real
  behaviour; no taxes; beyond the 24 months of data, growth comes from public averages; the content has 17 actions and
  2 shocks for now. More in [`docs/how-the-simulator-learns.md`](docs/how-the-simulator-learns.md#13-limits-said-honestly).

## Team

| Role | GitHub |
|---|---|
| Engine, model and integration | [@polma7](https://github.com/polma7) |
| UI/UX | [@OriolVines](https://github.com/OriolVines) |
| Data, content, AI and front end | [@caarlos-04](https://github.com/caarlos-04) |

Challenge materials (Stefania Stan, UBS × LauzHack) are used only to take part, under their terms. Fonts: IBM Plex
(SIL Open Font License, [`licenses/`](licenses/)).

## References

Research behind the main design choices.

### Financial digital twin of a person
- Anshari, M., Almunawar, M. N., & Masri, M. (2022). *Digital Twin: Financial Technology's Next Frontier of Robo-Advisor.* Journal of Risk and Financial Management, 15(4), 163. [MDPI](https://mdpi.com/1911-8074/15/4/163)
  — Defines a digital twin as a digital representation of an individual that integrates their data and generates analytics, recommendations and alternatives for personal financial management.
- Kretschmer & Post (2026). *Financial Planning Apps: User Engagement and Learning Effects.* Netspar Industry Paper 2026-38. [PDF](https://www.netspar.nl/wp-content/uploads/Netspar-Industry-Paper-2026-38-WEB.pdf)
  — Pension planning app with a "digital twin" feature tested with 127 users, who showed high interest in it.
- The digital twin concept goes back to Michael Grieves (2002); see `docs/digital-twin-concepts.md` in the challenge materials.

### Talking to your future self
- Hershfield, H. E., Goldstein, D. G., Sharpe, W. F., Fox, J., Yeykelis, L., Carstensen, L. L., & Bailenson, J. N. (2011). *Increasing Saving Behavior Through Age-Progressed Renderings of the Future Self.* Journal of Marketing Research, 48(SPL), S23–S37. doi:10.1509/jmkr.48.SPL.S23. [PubMed](https://pubmed.ncbi.nlm.nih.gov/24634544/)
  — Seeing an age-progressed version of yourself increased (hypothetical) saving; in one study people were willing to save about twice as much. Psychological basis for the future-self chat.
- Pataranutaporn, P., et al. (2024). *Future You: A Conversation with an AI-Generated Future Self Reduces Anxiety, Negative Emotions, and Increases Future Self-Continuity.* IEEE Frontiers in Education Conference (FIE 2024). [arXiv:2405.12514](https://www.arxiv.org/pdf/2405.12514v2) · [MIT Media Lab](https://www.media.mit.edu/publications/future-you-a-conversation-with-an-ai-generated-future-self-reduces-anxiety-negative-emotions-and-increases-future-self-continuity/)
  — LLM chatbot playing the user's future self (344 participants): lower anxiety, higher future-self continuity. Closest prior work to our chat; we ground it in numbers computed by the engine instead of a generated backstory, and let the user talk to their self at any moment of the life, not only at the end.

### Life-simulation games for financial decisions
- *Moonshot* study (2025). Education Sciences, 15(2), 227. [MDPI](https://mdpi.com/2227-7102/15/2/227)
  — Quasi-experiment with 293 students; game design variants made no difference between groups. Notes the lack of evidence on how to design financial-literacy games.
- Jones (2014). *Pecunia*, a life simulation where an 18-year-old character makes financial decisions and sees the consequences. [RPTEL](https://rptel.apsce.net/index.php/RPTEL/article/download/2014-09003/201)
  — Same "design your life" loop as ours; small pilot (16 participants).

### Modelling
- Cragg, J. G. (1971). *Some Statistical Models for Limited Dependent Variables with Application to the Demand for Durable Goods.* Econometrica, 39(5), 829–844. doi:10.2307/1909582. [Econometric Society](https://econometricsociety.org/publications/econometrica/1971/09/01/some-statistical-models-limited-dependent-variables-application)
  — The two-part ("hurdle") model for quantities that are often exactly zero: whether something happens and how big it is, each with its own equation. Our flow model splits every monthly flow (travel, bonus, unexpected expenses…) the same way.
- Deming, W. E., & Stephan, F. F. (1940). *On a Least Squares Adjustment of a Sampled Frequency Table When the Expected Marginal Totals are Known.* The Annals of Mathematical Statistics, 11(4), 427–444.
  — Origin of iterative proportional fitting: rescale one margin at a time until the table matches every known total. It is how the multiplicative factors of the flow model are fitted.
- Buja, A., Hastie, T., & Tibshirani, R. (1989). *Linear Smoothers and Additive Models.* The Annals of Statistics, 17(2), 453–510. doi:10.1214/aos/1176347115. [Project Euclid](https://projecteuclid.org/euclid.aos/1176347115)
  — Backfitting: fit an additive model one term at a time, holding the others fixed, until it converges. Our rules are additive on the log scale and fitted that way.
- Wallis, K. F. (2014). *The Two-Piece Normal, Binormal, or Double Gaussian Distribution: Its Origin and Rediscoveries.* Statistical Science, 29(1), 106–112. doi:10.1214/13-STS417. [arXiv:1405.4995](https://arxiv.org/abs/1405.4995)
  — A normal with a different spread on each side of the mode, as used by central banks for asymmetric forecast ranges. Our uncertain numbers (salaries, tuitions, rates) are two-piece normals through three quantiles of the data.
- Cocco, J. F., Gomes, F. J., & Maenhout, P. J. (2005). *Consumption and Portfolio Choice over the Life Cycle.* The Review of Financial Studies, 18(2), 491–533. doi:10.1093/rfs/hhi017. [IDEAS](https://ideas.repec.org/a/oup/rfinst/v18y2005i2p491-533.html)
  — Reference life-cycle model with risky labour income and borrowing constraints, solved numerically. The academic counterpart of a life lived decision by decision under uncertain income, which our game lets the user explore by hand.

### What is new in this project
Prior work covers each piece separately. We combine a person's financial digital twin learned from data, a
"design your life" game whose every consequence is computed and can be traced back to its source, a map to go back
and live other branches, and a chat with your self at any moment, grounded in those numbers.

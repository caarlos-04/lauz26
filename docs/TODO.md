# What is left to do

Checklist to agree on with the team. It says **what**, not how: each item gets an owner and an approach when we
discuss it. Owner column: A = `polma7` (engine), B = `OriolVines` (UI), C = `caarlos-04` (data + content + AI).

## 1. Model: numbers that make sense
The numbers the game produces today are not credible. Goal: every figure on screen can be defended in front of a judge.
- [ ] List the outputs that look wrong today (final net worth, cash, salaries, rents, tuition, per event and case)
- [ ] Define reference values for Alexia and 2–3 other profiles (what a reasonable result looks like after 10 years)
- [ ] Salaries: level by age, education and occupation; growth over the career
- [ ] Living costs and rent: level by city and household; growth over time
- [ ] Tuition and study length (bachelor, master, part-time master)
- [ ] Taxes and social contributions: decide whether to model them or state clearly that incomes are net
- [ ] Gross vs net income: one convention, used everywhere
- [ ] Investment return and financing rate: values and sources
- [ ] Case probabilities (pessimistic / neutral / optimistic) per event: values and sources
- [ ] Effects of every event checked one by one (amounts, durations, start months)
- [ ] Unemployment, job search and income gaps
- [ ] Inflation applied consistently to income, expenses and rent
- [ ] Sanity tests on the outputs (ranges per profile and event), not only accounting invariants
- [ ] Every assumption documented with unit (CHF, annual fraction) and source

## 2. Talk to your future self at any point
- [ ] After **any** decision (A or B) you can open the chat with your future self **at that point** of the life
- [ ] The future self only knows the life up to that point plus the computed consequences of that path
- [ ] Clear in the UI at which moment (age, turn, branch) you are talking
- [ ] Switching to another point or branch changes what the future self knows
- [ ] Decide: does the conversation of each point/branch persist or reset?
- [ ] Works offline (canned answers) with the same flow

## 3. Explore the other path at any moment (all 32 lives)
5 decisions × 2 options = 2⁵ = **32 possible lives** (with the same luck per turn).
- [ ] At any turn you can go back and take the other decision, and keep playing from there
- [ ] Every explored branch is kept: you never lose a life you already played
- [ ] By the end you can visit all 32 states
- [ ] View of the full tree: which branches are explored, which are not, where you are now
- [ ] Compare any two lives side by side (net worth, cash, life facts, key moments)
- [ ] Decide: is the luck per turn the same in every branch? (today: yes)
- [ ] Decide: what happens when a branch reaches a different event (eligibility depends on the life)
- [ ] Performance: computing or precomputing the 32 lives is fast on Android
- [ ] Chronicle and chat available for every branch (links to section 2)

## 4. The person's goals
- [ ] The person's goals are visible from the start (what, how much in CHF, by when, priority)
- [ ] Progress of each goal shown as % covered, at every point of the life
- [ ] Clear when a goal is reached or missed, and at which age
- [ ] See how each decision moves the goals before choosing (in the preview of the options)
- [ ] Compare goal coverage between branches / parallel lives
- [ ] Decide: where do the goals come from (interview, dataset, both) and can the user edit them?
- [ ] Decide: what a goal measures (cash or net worth) and say it in the UI
- [ ] The future self and the chronicle talk about the goals with the computed %

## 5. Analysis
- [ ] "Your story would change if...": sensitivity of the decision to each assumption and the break-even point
- [ ] Chart of the assumptions that move the result most
- [ ] "Optimal strategy": real exact solution or rename it honestly (today it is greedy)
- [ ] Editable assumptions in the app (some sliders) with recomputation
- [ ] Decide what stays from the noise band (`#12`)

## 6. Front end
- [ ] Review the whole flow screen by screen and decide what stays, what changes and what goes
- [ ] One consistent visual design (colours, typography, spacing, components)
- [ ] Clear navigation: always visible where you are in the life and what you can do
- [ ] Charts readable and labelled (units in CHF, axes, legends)
- [ ] Simplify: fewer concepts on screen at the same time
- [ ] Good on phone and desktop sizes
- [ ] Dark/light mode checked on every screen
- [ ] ES/EN complete, no hard-coded strings
- [ ] Basic accessibility (contrast, text size, touch targets)
- [ ] Clear loading, empty and error states (e.g. LLM offline)
- [ ] Remove fake/mock paths that are no longer used

## 7. Trust and transparency
- [ ] The chat's numbers are checked against the engine (nothing invented, and we can show it)
- [ ] Validation panel visible in the app (cash identity, attribution, reproducible seed, backtest error)
- [ ] Assumptions, limitations and "not financial advice, not a prediction" always reachable
- [ ] Which data are synthetic, which are public and which are assumed, stated clearly

## 8. Delivery
- [ ] README with setup and how to reproduce one scenario
- [ ] Demo script (≈3 min) with Alexia and backup plan if the network fails
- [ ] Answers ready for the judges' questions (assumptions, validation, constraints, next steps)
- [ ] JetBrains: what is shared in `commonMain` vs platform-specific
- [ ] JetBrains Air: notes on what each agent did and what we corrected
- [ ] Pitch slot booked

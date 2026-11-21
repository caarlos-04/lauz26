# AGENTS.md

Instructions for the AI agents (Claude Code, etc.) working in this repository.

## Context
Read `docs/README.md` before you start: it holds the plan, the architecture, the work split and the decisions taken.

## Git
- **Do not commit without asking** the user. Show first what you are going to include.
- **Do not add `Co-Authored-By`** or any AI attribution line to commits or PRs.
- **Never use `git push --force`** or `--force-with-lease` without explicit permission.
- Do not `push` without asking.
- Do not rewrite history (`rebase`, `commit --amend`, `reset --hard`) of commits that are already published.
- Do not work directly on `main`: use your own branch and ask before merging.
- Make small commits with clear messages.
- Do not delete branches or files of other teammates without asking.

## Coordination between developers (GitHub issues)

Use `gh` for everything in this section. Repo: `caarlos-04/lauz26`.

### Who is who
| Role | GitHub user | Label | Responsibility (details in `docs/README.md`, section 11) |
|---|---|---|---|
| Dev A – Engine + integration | `polma7` | `polma7` | KMP skeleton, `model/`, engine, decision tree, attribution, tests |
| Dev B – UI/UX (front end) | `OriolVines` | `OriolVines` | Screens, life line, charts, i18n, packaging |
| Dev C – Data + content + AI | `caarlos-04` | `caarlos-04` | Calibration, events, facts, assumptions, chronicle and chat |

Each person has a label named after their GitHub user. When a session starts, find out who you work for with
`gh api user --jq .login` and use their role and label.

### When you need something from another developer: open an issue
If you are missing something that is someone else's responsibility (a change in `model/`, an engine function, a screen,
a content file...), **do not do it yourself in their module**: open an issue and tag them.
1. First check whether it already exists: `gh issue list --state open --search "<keywords>"`.
2. Create it assigned to that person, with their label, mentioning them in the body:
   ```
   gh issue create --title "[caarlos-04 → polma7] <what is needed>" \
     --assignee <user> --label <user> \
     --body "@<user> <body using the template below>"
   ```
3. Issue body:
   - **What I need**: one sentence.
   - **What for**: what it blocks (file, screen, flow step P1-P12).
   - **Contract**: affected data class, function or JSON file, with a link to the section of `docs/README.md`.
   - **When**: the schedule hour when you need it, and whether it blocks you now.
   - **Who asks**: your role and your branch.
4. If it is a **contract change** (`model/`, JSON formats), add the `contrato` label and mention everyone affected.
5. Tell the user which issue you created (number and link).

### Every time you push: check whether you have been tagged
Right after every `git push` (and also when the session starts):
1. Look for open issues that concern you:
   ```
   gh issue list --state open --assignee @me
   gh issue list --state open --label <your-user>
   gh search issues --repo caarlos-04/lauz26 --state open --mentions <your-user>
   ```
2. Read each one with its comments: `gh issue view <n> --comments`. Look for new comments since your last reply.
3. For each issue, do one of these two things:
   - **If they ask for information or status**: reply with a comment (`gh issue comment <n> --body "..."`) giving the
     status (done / in progress / blocked), what is missing, branch and commit if relevant, and when it will be ready.
   - **If they ask for code**: implement it in your branch and push it **following the Git rules above** (show the user
     what you will include and ask for confirmation before the commit and the push). Then comment on the issue with the
     branch, commit or PR (`Closes #<n>` in the PR description).
4. Do not close someone else's issue without confirmation from whoever opened it; close your own when they are resolved.
5. Summarise for the user which issues you found and what you replied or pushed.

Issues and comments in English, short and concrete. Do not mention people who are not involved.

## Security and data
- **Never** commit API keys, tokens or credentials. Use environment variables and make sure they are in `.gitignore`.
- Use **synthetic** data only. No real personal or financial data.
- Do not include third-party content without checking its licence.

## Teamwork
- We are 3 developers working in parallel. Only touch your module; if you need to change a shared contract
  (models, interfaces), say so first.
- Logic goes in `commonMain` whenever possible; platform-specific code only if there is no alternative.
- If something is unclear, **ask before making things up**.

## Code
- Kotlin Multiplatform + Compose Multiplatform. Android + Desktop (JVM).
- The financial engine is a pure function and must have tests.
- Financial figures and assumptions: document units (CHF) and source. The LLM never makes up numbers: it only explains
  the ones computed by the engine.
- Do not add new dependencies without discussing it with the team.
- UI texts in ES/EN, never hard-coded strings in the code.
- Comments, variable names and everything else in English.

# TOKEN_RULE.md

## 1. Purpose

This file defines how AI should manage context and token usage across all team projects.

The goal is to read enough to be correct, but not so much that the assistant becomes slow, noisy, or unfocused.

---

## 2. Context Loading Order

Start with the smallest useful context:

1. The user's latest message.
2. Relevant task file in `.ai/tasks/`, if one exists.
3. `.ai/global/RULE.md`.
4. `.ai/global/PRODUCT.md` when product behavior, user flow, copy, permissions, monetization, privacy, analytics, or scope is involved.
5. `.ai/global/TECH_STACK.md` when code, build, architecture, API, data, or test behavior is involved.
6. Repository files directly related to the task.
7. Wider search only when the direct path is unclear.
8. External docs only when the user asks, the topic is time-sensitive, or repository context is not enough.

Do not read every global file for every small task.

---

## 3. Search Before Reading Large Files

Prefer discovery commands before opening large files:

- File list: `rg --files`.
- Text search: `rg "pattern"`.
- Symbols/classes/functions: use targeted search patterns.
- Git state: `git status --short`.
- Diffs: `git diff -- path`.

Open only the files and ranges needed to answer the task.

---

## 4. Avoid Low-Value Context

Do not load these unless the task specifically needs them:

- Generated build output.
- Dependency caches.
- Binary assets.
- Lockfiles larger than needed for the question.
- Minified bundles.
- IDE metadata.
- Coverage reports.
- Large logs unrelated to the failing path.
- Old docs that are not referenced by the current task.

If a large file is relevant, summarize the important parts instead of repeating it.

---

## 5. Context By Task Type

### Bug fix

Read:

- Bug/task brief.
- User-facing flow or failing command.
- Files in the failing path.
- Logs/errors/stack traces.
- Tests around the behavior.

Avoid:

- Whole-repo architecture review unless root cause is unclear.

### Feature

Read:

- Product context.
- Existing similar flows.
- State/data/API boundaries.
- UI/components or backend contracts that the feature touches.
- Tests for similar behavior.

Avoid:

- Building a broad system redesign unless requested.

### UI

Read:

- Current screen/component.
- Existing design system/component patterns.
- State owner and event handlers.
- Assets/styles/tokens only if needed.

Avoid:

- Unrelated screens with different design patterns.

### API/Data

Read:

- Route/client/schema/model.
- Repository/service/use-case.
- Persistence/migration paths.
- Tests and error handling.

Avoid:

- UI files unless user-facing behavior changes.

### Build

Read:

- Build config files.
- Version catalogs/package manifests.
- Failing build output.
- CI config if CI is affected.

Avoid:

- Application feature code unless dependency usage is unclear.

### Docs-only

Read:

- Docs requested by the user.
- Minimal repository context needed for accuracy.

Avoid:

- Source code edits.

---

## 6. Output Discipline

Keep responses focused:

- Do not paste long files back to the user.
- Do not repeat generic rules unless they matter to the decision.
- Prefer concise summaries, changed-file lists, verification, and risks.
- Quote only the exact line or small snippet needed.
- Use bullet lists only when they improve scanning.
- Keep final answers shorter than the working context.

---

## 7. When To Ask For Clarification

Ask only when:

- Two reasonable interpretations would lead to different files or user-visible behavior.
- The task may require destructive changes.
- The required product decision is not in `PRODUCT.md` or the task.
- External credentials, accounts, API contracts, or private business rules are needed.

Otherwise, state a reasonable assumption and proceed.

---

## 8. When To Stop Expanding Context

Stop reading more files when:

- The relevant implementation path is clear.
- The root cause is supported by code/log evidence.
- The change boundary is known.
- A targeted verification path is available.

If confidence is still low, explain the uncertainty and choose the next smallest useful investigation step.

---

## 9. Team Convention

Keep `RULE.md`, `TECH_STACK.md`, and `TOKEN_RULE.md` generic enough to reuse across repositories.

Put project-specific facts in:

- `PRODUCT.md`
- task files under `.ai/tasks/`
- project README/docs
- code comments only when the code itself needs clarification

Do not turn shared base files into app-specific documentation.

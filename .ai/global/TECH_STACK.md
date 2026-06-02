# TECH_STACK.md

## 1. Purpose

This file is the team-wide technical context rule.

It should guide AI to discover and respect the tech stack of any repository. Do not hardcode one project's framework, architecture, commands, or folder structure here. Project-specific implementation details may be summarized in `PRODUCT.md`, task files, or a project-local technical note when needed.

AI must inspect the repository before assuming the stack.

---

## 2. Stack Discovery Checklist

At the start of a technical task, identify only what is relevant:

- Project type: mobile, web, backend, desktop, CLI, data, ML, infrastructure, library, monorepo.
- Languages and runtime versions.
- Package/build tools.
- Frameworks and platform SDKs.
- App/module boundaries.
- Entry points.
- Routing/navigation patterns.
- State management patterns.
- Data/storage layers.
- API/network clients.
- Test framework and common commands.
- Code generation/build artifacts.
- CI/lint/format commands.
- Deployment/release constraints.

Use repo files as source of truth: manifests, package files, Gradle/Maven files, lockfiles, config files, workspace files, README/docs, and existing code patterns.

---

## 3. Architecture Principles

Respect the architecture already present.

Common layer intent:

- UI/view layer: render state and capture user intent.
- State/controller layer: own UI state, user actions, navigation coordination, and lifecycle-safe orchestration.
- Domain/application layer: enforce business rules and use cases.
- Data layer: coordinate repositories, cache, database, file system, and network sources.
- API/service layer: define external contracts and request/response handling.
- Model layer: represent domain, DTO, persistence, and UI models according to existing conventions.
- Utility/shared layer: hold reusable helpers without becoming a dumping ground.

Do not introduce a new architecture style unless the task requires it and the trade-off is documented.

---

## 4. Platform-Agnostic Engineering Rules

For all stacks:

- Keep public contracts stable.
- Keep state ownership clear.
- Keep side effects explicit.
- Keep async work cancellable and lifecycle-safe.
- Avoid blocking the main/UI/event loop thread.
- Validate external input.
- Handle partial failure.
- Keep logs useful but safe.
- Keep errors actionable for users and useful for developers.
- Avoid over-abstracting before there is real duplication or complexity.

---

## 5. Frontend And Mobile Rules

When working on UI:

- Use the existing UI framework and design system.
- Reuse existing components, tokens, assets, themes, and layout primitives.
- Keep visual hierarchy clear.
- Preserve responsive behavior.
- Handle loading, empty, error, disabled, selected, offline, and permission-denied states when relevant.
- Prevent text overflow and incoherent overlap.
- Keep accessibility basics: labels/content descriptions, focus order, touch target size, contrast, keyboard/screen-reader behavior where applicable.
- Do not add marketing/landing-page structure when the task asks for an app/tool workflow.

For mobile:

- Respect activity/view/controller lifecycle.
- Handle runtime permissions near the feature that needs them.
- Consider background execution limits, notifications, app review rules, and device capability differences.

---

## 6. Backend And API Rules

When working on backend/API code:

- Check route definitions, controllers/handlers, services, schemas, and clients.
- Preserve request/response compatibility unless a versioned change is intended.
- Validate input at boundaries.
- Handle auth, authorization, rate limits, idempotency, retries, and timeouts as appropriate.
- Keep database transactions and migrations safe.
- Avoid leaking internal errors or sensitive data.
- Add tests for contract, validation, and failure paths when behavior changes.

---

## 7. Data And Persistence Rules

When changing stored data:

- Identify schema, model, migration, serializer, and all read/write paths.
- Plan backward compatibility and rollback behavior.
- Handle missing, old, corrupt, null, and large data.
- Avoid destructive migrations without explicit approval.
- Do not change analytics or persisted field names casually.
- Keep personally sensitive data minimized and protected.

---

## 8. Networking Rules

When working with network code:

- Keep request/response models clear.
- Handle success, empty response, malformed response, timeout, offline, server error, auth error, retry, and cancellation.
- Do not perform network work on UI/main thread.
- Do not assume external services are always available.
- Avoid logging secrets, tokens, full payloads with personal data, or private URLs unless intentionally sanitized.
- Keep base URLs, environment flags, and credentials in the project's established config mechanism.

---

## 9. Build And Dependency Rules

Before changing build files:

- Identify package manager/build tool and lockfile policy.
- Check whether dependency versions are centralized.
- Prefer minimal version changes.
- Avoid unrelated dependency upgrades.
- Explain why a new dependency is necessary.
- Run the narrowest relevant build/test command.

Never edit generated outputs as a substitute for source/config changes.

---

## 10. Testing Strategy

Match tests to risk:

- Pure logic: unit tests.
- Data transformation: unit tests with edge cases.
- API contract: handler/client tests and schema validation.
- UI behavior: component/screen tests or manual checks when automation is not available.
- Persistence: migration/read-write compatibility tests.
- Async/background jobs: cancellation, retry, failure, and lifecycle tests.
- Security/permissions: denied/expired/invalid-token paths.

If the project has no test coverage for the touched area, note the gap and provide a manual checklist.

---

## 11. Documentation Expectations

Tech docs should record:

- Current architecture decisions that affect future work.
- Setup/build/test commands.
- Non-obvious constraints.
- External contracts.
- Known limitations.

Do not put product vision in `TECH_STACK.md`; keep product behavior in `PRODUCT.md`.

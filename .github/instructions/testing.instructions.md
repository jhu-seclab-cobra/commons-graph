---

description: apply only when modifying any files about testing

alwaysApply: false

---

# Testing Standards

## 1. Layout
- Select ONE layout profile and apply repo-wide.
- Profile A: `src/test/kotlin/`; files `<Component>Test.kt`
- Profile B: `src/test/kotlin/`; files `Test<Component>.kt`
- Profile C: co-located near source; files `<Component>Test.kt`
- Profile D: dedicated tree `src/test/kotlin/`; mirror packages/namespaces (recommended for Kotlin)
- Profile E: inline unit in source; integration in `src/test/kotlin/`

## 2. Types
- Unit: isolated from external systems; single unit per test file where feasible.
- Integration: cross-module or external interaction; run separately from unit.

## 3. Structure
- Use Arrange → Act → Assert.
- One behavior per test case.
- Name test functions: `test <component> <condition> <expected>` using backticks (e.g., `` `test addNode when node exists throws exception` ``) or `test<Component><Condition><Expected>` (e.g., `testAddNodeWhenNodeExistsThrowsException`).
- Group test cases in test classes (Kotlin test framework requires classes).

## 4. Naming & Location
- Mirror source module/component layout under the selected profile.
- Use one of: `<Component>Test.kt` | `Test<Component>.kt` (prefer `<Component>Test.kt`).
- Place test classes in the same package structure as source classes.

## 5. Fixtures & Data
- Centralize reusable setup via `@BeforeTest` and `@AfterTest` annotations.
- Use `lateinit var` or lazy initialization for test fixtures.
- Provide scenario-specific setup per test when needed.
- Use temporary resources for file/network/process interactions; auto-clean in `@AfterTest`.
- Use `use` function for resources that implement `Closeable`/`AutoCloseable`.

## 6. Assertions
- Prefer one focused assertion per test; add more only when verifying a single behavior.
- Use specific assertion forms from `kotlin.test` (e.g., `assertEquals`, `assertTrue`, `assertNotNull`, `assertFailsWith`) over generic boolean checks.
- Assert negative paths and error conditions explicitly using `assertFailsWith<ExceptionType>`.

## 7. Mocking
- Mock only at system boundaries of the unit under test.
- Prefer dependency injection to enable mocking.
- Use mocking libraries (e.g., MockK) for test doubles (mocks/stubs/fakes) with verified critical interactions.
- Do not over-mock; prefer real behavior for internal collaborators.
- Use interfaces for dependencies to enable easy mocking.

## 8. Configuration
- Declare test framework (kotlin.test or JUnit) in `build.gradle.kts`.
- Configure test discovery rules, markers/tags, and strict/fail-fast options in Gradle.
- Use `@Test` annotation from `kotlin.test` or JUnit.
- Keep environment setup deterministic (fixed seeds, stable paths, isolated temp dirs).

## 9. Execution
- Default command (`./gradlew test`) runs unit tests only; provide a separate task/marker for integration tests.
- Enable parallel execution in Gradle test configuration.
- Use concise stack traces; stop-on-first-failure mode for debugging.
- Use `./gradlew test --continue` to run all tests even if some fail.

## 10. Coverage
- Target coverage ≥ 90% for statements/branches on changed modules.
- Use Kover for Kotlin code coverage measurement.
- Measure coverage for `src/main/kotlin` only; exclude generated code and vendor deps.
- Report missing lines; fail CI below threshold.
- Run `./gradlew koverHtmlReport` to generate coverage reports.

## 11. Maintenance
- Add tests alongside new/changed code in the same change set.
- Keep tests independent; no shared mutable state across tests.
- Refactor tests to remove duplication (helpers, fixtures, factories).

## 12. Agent Outputs (when generating human docs)
- Produce a minimal runnable example per public API in the requirements doc.
- Include test snippets demonstrating Arrange–Act–Assert for typical and error cases using Kotlin test syntax.
- Document how to run unit vs integration tests for the selected layout profile (e.g., `./gradlew test` for unit tests).
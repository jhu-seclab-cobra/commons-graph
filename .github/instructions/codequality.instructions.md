---
description: use those rules if and only if related to codes and programming
alwaysApply: false
---
# Code Quality Standards

## Control Flow Optimization
- Prefer early returns over nested conditionals.
- Use guard clauses to minimize nesting and improve readability.
- Use `continue` to skip iterations instead of nested `if` blocks.
- Use `break` to exit loops when conditions are met.
- Keep logic flat and avoid deep nesting.

## Error Handling
- Catch specific exception types (avoid broad exception catching).
- Handle errors at the appropriate abstraction level.
- Preserve original exception context across call chains.
- Provide meaningful error messages.

## Code Structure
- Keep functions short (ideally < 20 lines).
- Extract complex logic into dedicated functions.
- Avoid deep nesting (max 3 levels).
- Use meaningful variable and function names.

## Performance
- Use `use` function or `Closeable`/`AutoCloseable` for resource management/cleanup.
- Avoid unnecessary object creation in loops.
- Check caches before expensive operations.
- Optimize critical paths.
- Prefer immutable collections when possible.
- Use lazy initialization for expensive computations.

## Documentation
- Use English for comments and docs.
- Explain why (rationale) rather than what.
- Add explicit type declarations for function parameters and return values when type inference is unclear.
- Prefer specific types over generic ones.
- Use KDoc format for public APIs.

## Testing
- Prefer one assertion per test when practical.
- Use descriptive test names that state scenario and expectation.
- Follow Arrange–Act–Assert pattern.
- Keep tests focused and isolated; use fixtures for common setup.
- Cover positive and negative paths; assert error conditions explicitly.

## API Design
- Use core module APIs directly.
- Avoid direct dependencies on infrastructure services.
- Prefer exception-driven error handling.
- Build self-contained modules.
- Keep data containers passive; avoid embedding behavior in simple data models.
- Represent application logic as explicit callables/functions.

## Code Quality Workflow
### Pre-Modification Quality Check
- Run code quality tools on target files
- Check current score and identify issues
- Review and understand warnings before making changes

### During Development
- Follow control flow optimization guidelines
- Follow Kotlin naming conventions and code structure rules
- Add explicit type declarations when type inference is unclear
- Add KDoc documentation for public APIs
- Use `use` function or `Closeable` for resource management
- Use appropriate Kotlin design patterns (sealed classes, data classes, extension functions)

### Post-Modification Quality Check
- Run code quality tools again (ktlint, detekt)
- Target score: 9.0+ for all modules
- Fix all warnings before proceeding
- Apply Kotlin code formatting (ktlint format)
- Organize imports (remove unused, sort)
- Verify structure follows Kotlin standards

### Phase Completion Quality Gate
- Run comprehensive code quality checks
- Check all files formatted consistently
- Run all unit tests
- Verify test coverage and quality
- Verify no critical issues remain
- All quality gates must pass before proceeding to next phase

## External Tool Integration
### Context7 Library Documentation
- Use Context7 to resolve library IDs and get documentation
- Get documentation for external libraries before integration
- Focus on specific topics relevant to current implementation

### DeepWiki Repository Exploration
- Use DeepWiki to explore repository structure
- Ask specific questions about implementation patterns
- Get repository structure before diving into code

### Documentation Policy
- **NEVER** create summary/README documents after task completion
- **NEVER** create TEST_UPDATE_GUIDE.md or similar summary files
- Update existing TODO files in place
- Keep documentation concise and integrated in existing structure
- Avoid redundant documentation that duplicates TODO content

## Language-Specific Guidelines
### Kotlin Best Practices
- Follow Kotlin coding conventions (https://kotlinlang.org/docs/coding-conventions.html)
- Use appropriate Kotlin design patterns (sealed classes, data classes, extension functions, coroutines)
- Maintain consistency with existing codebase style
- Use Kotlin-specific tools for quality assurance (ktlint, detekt, kover)

### Code Organization
- Follow Kotlin package organization (package declaration at top, imports below)
- Use appropriate import patterns (avoid wildcard imports except for common packages)
- Maintain clear separation of concerns
- Avoid circular dependencies
- Prefer top-level functions and extension functions over utility classes
- Use companion objects for static members
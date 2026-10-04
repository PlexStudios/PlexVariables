# PlexVariables implementation plan

> **For agentic workers:** Use the supplied design and exact interfaces. Work only in your assigned files. Steps use checkbox syntax for tracking.

**Goal:** Deliver a complete compilable Phase 1 plugin, tests, operator documentation, and built JAR.

**Architecture:** Immutable snapshots separate asynchronous loading from frequent in-memory resolution. Commands, PAPI lifecycle, and Adventure messages integrate these services on the main thread.

**Tech Stack:** Java 21, Gradle, Paper API 1.21, PlaceholderAPI, Adventure, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-01-plexvariables-design.md`

## Global constraints

- Java 21; package `dev.plex.plexvariables`; identifier `plexvar`.
- Required PlaceholderAPI, both server APIs compileOnly, no shaded dependencies.
- No disk or network access in resolution; no global cache of player results.
- No Phase 2 implementations; all user messages configurable; prefix opt-in.

## Review focus

- Recursive callback from an external expansion must share the same guard.
- Exponential acyclic reference trees must stop at a work/output budget.
- Reload must never expose partially loaded state or publish after disable.
- Replacement text containing `%prefix%` or another parameter must remain literal.
- Bad files must not erase variables loaded from unrelated files.

## Task 1: Resolver and model

Files: `variable/VariableDefinition.java`, `variable/VariableType.java`, `variable/VariableResolver.java`, `variable/ResolutionWarnings.java`, `variable/VariableResolverTest.java`.

Interfaces: as specified in the design; consume PluginState and PluginSettings, inject external parser.

- [ ] Write tests for literals, case normalization, cross references, changing/null player values, unknowns, cycles, depth, work/output bounds, callback reentry, snapshot stability, concurrency, and warning throttling.
- [ ] Run tests to establish missing behavior; implement guards and minimal resolution path; rerun targeted tests.
- [ ] Review implementation for allocations, state leaks, exception cleanup, and source-aware warnings.

## Task 2: Loader and configuration

Files: `variable/VariableManager.java`, `config/PluginSettings.java`, `config/PluginState.java`, `config/ConfigurationLoader.java`, `variable/VariableManagerTest.java`, `config/ConfigurationLoaderTest.java`, `resources/config.yml`, `resources/variables/general.yml`, `resources/variables/examples.yml`.

Interfaces: as specified in the design; consume model and MessageUtil.

- [ ] Write temporary-directory tests for discovery, deterministic duplicates, invalid YAML/IDs/values, unsupported types, missing sections, fresh snapshots, and settings validation.
- [ ] Establish failing tests, implement isolated file loading and immutable output, then run targeted tests.
- [ ] Verify new and removed files are reflected on reload, with no stale entries.

## Task 3: Integration, messages, and build

Files: Gradle build/wrapper, plugin entry point, command, expansion, ReloadService, ColorUtil, MessageUtil, remaining resources and util tests.

- [ ] Set Java 21 toolchain and API dependencies; generate pinned Gradle wrapper.
- [ ] Test colors and single-pass message substitution, including optional prefix and multiline messages.
- [ ] Implement plugin lifecycle, async reload publication, paginated permission-aware commands and completion.
- [ ] Build and inspect the actual JAR; verify Java 21 class version, plugin metadata and absent shaded APIs.

## Task 4: Review and delivery

Files: README.md, docs/TESTING.md, any corrections found during review.

- [ ] Perform independent review against the original request and fix material findings with regression coverage.
- [ ] Run `gradlew.bat clean build` and record real results.
- [ ] Document setup, configs, build commands, expected console output, test/edge-case checklist, and PAPI verification commands.
- [ ] Deliver source project and JAR with exact validation and live-server limitations.

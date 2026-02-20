# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- You can see CLJS compile status (warnings/errors, as well as completion) using `src/dev/shadow_cljs_checker.clj`
- Shadow-cljs will usually be running already, as will a Clojure REPL, use clojure-mcp to access REPLs for these

## AI Helper Documentation

The `docs/ai/` directory contains comprehensive reference material for AI assistants:

**Testing Guides:**
- `writing-tests.md` - Complete guide to writing tests with Fulcro Spec (assertions, mocking, spying, error handling)
- `running-tests.md` - How to run tests via REPL using Kaocha

**Clojure Tooling:**
- `clojure-library-source-and-documentation.md` - Using `clojure.repl/doc` and `clojure.repl/source` to access library documentation

**Fulcro Framework Documentation:**
Since this library integrates with Fulcro for visualization and UI state management, these docs cover:
- `02-fulcro-overview.md` - Fulcro philosophy, graph-centric design, architecture comparison
- `03-core-concepts.md` - Immutability, pure rendering, data-driven architecture, graph database structure
- `04-getting-started.md` - Project setup, component anatomy, data flow basics
- `06-data-loading.md` - Loading patterns, targeting, load markers, error handling
- `07-core-api.md` - Core functions: `db->tree`, `tree->db`, normalization, denormalization
- `08-components-and-rendering.md` - Component definition with `defsc`, DOM factories, lifecycle methods
- `09-server-interactions.md` - Server setup, Pathom resolvers, mutations, error handling
- `18-eql-query-language.md` - EQL syntax: properties, joins, unions, mutations, recursive queries
- `20-normalization.md` - How normalization works, idents, graph structure, merge operations
- `21-full-stack-operation.md` - Full-stack patterns, targeting rules, server integration, user-triggered loading

**When to Reference These Docs:**
- Writing or debugging tests → `writing-tests.md`, `running-tests.md`
- Working with Fulcro integration code in `integration/fulcro*.cljc` → Fulcro docs
- Understanding visualization UI → Fulcro component and rendering docs
- Need library docs/source → `clojure-library-source-and-documentation.md`

## Project Overview

This is a Clojure/ClojureScript (CLJC) implementation of SCXML-compliant state charts. The library follows the W3C 2015-09-01 SCXML recommendation for state chart semantics and processing algorithms, but uses Clojure data structures instead of XML.

**Key Characteristics:**
- Production-ready, API-stable library
- Cross-platform (CLJ/CLJS) with .cljc files
- Protocol-based, highly extensible architecture
- Supports distributed systems via serializable working memory
- MIT licensed

### Building Documentation

```bash
make docs/index.html
# Generates HTML from Guide.adoc using asciidoctor
```

### Development REPL

The project uses deps.edn with several aliases. Start a REPL with:
```bash
clojure -A:dev:test
```

Shadow-cljs nREPL runs on port 9000 (configured in shadow-cljs.edn).

## Architecture Overview

The library uses **protocol-based extensibility** around 7 core components. Each major subsystem has its own CLAUDE.md with deep-dive details:

1. **Processor** — SCXML algorithm (see `algorithms/CLAUDE.md`)
2. **DataModel** — Data storage/retrieval (see `data_model/CLAUDE.md`)
3. **ExecutionModel** — Expression interpretation (`execution_model/lambda.cljc`)
4. **EventQueue** — Event delivery (see `event_queue/CLAUDE.md`)
5. **WorkingMemoryStore** — Session persistence (`working_memory_store/`)
6. **StatechartRegistry** — Chart definitions (`registry/`)
7. **InvocationProcessors** — External service invocation (see `invocation/CLAUDE.md`)

For **Fulcro integration**, see `integration/CLAUDE.md`. The `routing/` sub-package under `integration/fulcro/` provides the new routing system with URL sync, cross-chart routing, and configuration validation (replaces the deprecated `ui-routes` namespace).

Internally uses **imperative style with volatiles** (matching W3C pseudocode); externally exposes a **functional interface**.

### State Chart Structure

Charts are **nested maps** processed in document order (depth-first by default, configurable to breadth-first via `::sc/document-order`).

**Element types:**
- `statechart` - Root container
- `state` - Atomic or compound state
- `parallel` - Parallel state (all regions active)
- `transition` - Event-driven or eventless transitions
- `initial`, `final`, `history` - Special state types
- `on-entry`, `on-exit` - Entry/exit handlers
- `invoke` - Start external services/charts
- Executable content: `script`, `assign`, `send`, `raise`, `log`, `cancel`

**Chart example pattern:**
```clojure
(statechart {:initial :start}
  (state {:id :start}
    (on-entry {} (script {:expr (fn [env data] ...)}))
    (transition {:event :next :target :end}))
  (final {:id :end}))
```

### Working Memory vs Sessions

- **Working Memory**: Serializable EDN map containing configuration (active states) and data model state
- **Session**: Running instance of a chart, identified by unique session-id
- **Session ID**: Used for event routing, persistence, and cross-chart communication

## Development Patterns

### Defining Charts

Use element functions from `com.fulcrologic.statecharts.elements`:
```clojure
(require '[com.fulcrologic.statecharts.chart :as chart]
         '[com.fulcrologic.statecharts.elements :refer [state transition script]])
```

**Convenience helpers** (`convenience.cljc`, `convenience-macros.cljc`) simplify common patterns:
- `(on :event :target-state ...)` instead of verbose transition
- `(choice {...} pred1 :state1 pred2 :state2 :else :default)` for condition nodes
- `(send-after {...})` for delayed events with auto-cancel on exit

### Testing

Use `com.fulcrologic.statecharts.testing` namespace:
- `new-testing-env` - Creates test environment with mock execution model
- `goto-configuration!` - Jump to specific state for test setup
- `configuration-for-states` - Calculate full configuration from leaf states
- `run-events!` - Process events and inspect results
- Mock expressions by providing `{pred-fn true, script-fn (fn [env] ...)}` map

### Fulcro Integration

Special features when integrating with Fulcro apps:
- **Actors**: Map UI components to state chart data paths (`:actor/thing`)
- **Aliases**: Named paths to data (`:fulcro/aliases`)
- **Operations**: `fop/load`, `fop/invoke-remote` for I/O
- **Hooks**: `use-statechart` for component-local charts

## Important Constraints

### Guardrails

The codebase uses Fulcrologic Guardrails for spec checking. When running tests or development, guardrails must be enabled:
```
-J-Dguardrails.enabled=true -J-Dguardrails.config=guardrails-test.edn
```

### SCXML Conformance

Any deviation from SCXML standard behavior (transitions, entry/exit order, etc.) is considered a **bug**. Bugfixes may change behavior if code relied on incorrect semantics.

**Not implemented:**
- Flow control executable content (if/else/foreach) - but extensible via multimethods
- Complete SCXML test suite coverage

### Cross-platform Considerations

- Most files are `.cljc` for portability
- `invocation/future.clj` is CLJ-only
- CLJS builds use Shadow-cljs
- Browser tests run via Karma + Chrome

## Common Gotchas

1. **Document order matters**: State IDs must be unique, transitions are checked in order
2. **Working memory is immutable**: Each event processing returns new working memory
3. **Session IDs are global**: Restarting with existing ID overwrites the session
4. **History nodes have strict rules**: Shallow history requires exactly one target, see `chart.cljc:invalid-history-elements`
5. **Executable content is data**: Functions, not code - allows for serialization strategies

## Project Tracking

Specs and project tracking live in `docs/ai/specs/`:
- `TRACKER.md` — Single source of truth for project status
- `WORKFLOW.md` — Agent-centric conductor pattern guide
- Individual spec files — One per feature/bug/improvement

## CLAUDE.md Hierarchy

Subdirectory CLAUDE.md files provide targeted context:
- `algorithms/CLAUDE.md` — W3C SCXML algorithm details
- `data_model/CLAUDE.md` — Data flow and operations
- `event_queue/CLAUDE.md` — Event delivery mechanisms
- `integration/CLAUDE.md` — Fulcro integration patterns
- `invocation/CLAUDE.md` — External service invocation

Update subdirectory CLAUDEs when discovering gotchas. Keep them under 50 lines. Detail goes in the closest module CLAUDE.md; root stays cross-cutting.

## Dependencies of Note

- `com.fulcrologic/guardrails` - Spec validation
- `metosin/malli` - Schema validation
- `org.clojure/core.async` - Event loops
- `org.eclipse.elk/*` - Graph layout for visualization
- `com.fulcrologic/fulcro` (optional, dev only) - Framework integration

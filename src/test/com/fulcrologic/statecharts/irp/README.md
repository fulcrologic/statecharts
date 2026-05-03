# IRP Port — Agent Briefing

We are porting the **W3C SCXML 1.0 (2015-09-01) IRP automated tests** into Clojure under `com.fulcrologic.statecharts.irp`. Source: https://www.w3.org/Voice/2013/scxml-irp/manifest.xml. Each automated test, when run against a conforming processor, drives the chart to a final state with id `pass` (success) or `fail` (failure).

## File layout

- One file per test: `src/test/com/fulcrologic/statecharts/irp/test_<N>.cljc`
- Namespace: `com.fulcrologic.statecharts.irp.test-<N>`
- Shared helper namespace: `com.fulcrologic.statecharts.irp.runner`

## Per-test workflow (implementers)

1. Claim a task via TaskUpdate with `owner` = your agent name. Move to `in_progress`.
2. Fetch the .txml: `curl -sf https://www.w3.org/Voice/2013/scxml-irp/<N>/test<N>.txml`
3. Translate to Clojure data structures using `com.fulcrologic.statecharts.elements`. See *Translation rules* below.
4. Write the test file. Skeleton:

```clojure
(ns com.fulcrologic.statecharts.irp.test-<N>
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [state parallel final transition
                                                    on-entry on-exit raise script
                                                    assign data-model data Send
                                                    log cancel invoke finalize]]
    [com.fulcrologic.statecharts.irp.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def chart-<N>
  (chart/statechart {:initial :s0}
    ;; ... ported chart ...
    ))

;; events to send (in order). Use [] for tests that need no external events.
(def events [])

(specification "IRP test <N> — <one-line description from .txml comment>"
  (assertions
    "reaches pass"
    (runner/passes? chart-<N> events) => true))
```

5. Run only THIS test file via the REPL (port file: `/Users/tonykay/fulcrologic/statecharts/.nrepl-port`):

```bash
clj-nrepl-eval -p $(cat /Users/tonykay/fulcrologic/statecharts/.nrepl-port) <<'EOF'
(do
  (require '[com.fulcrologic.statecharts.irp.runner] :reload)
  (require '[com.fulcrologic.statecharts.irp.test-<N>] :reload)
  (require '[kaocha.repl :as k])
  (k/run 'com.fulcrologic.statecharts.irp.test-<N>))
EOF
```

6. **PASS:** Mark task `completed`, claim next.
7. **FAIL:** Set task metadata `{:status-detail "failing", :err "<truncated repl output>"}`, leave `in_progress`, message `critique` with: test number, your translation reasoning, the .txml source, and the failing output. Then move on to claim the next task — critique will route the report.

## Translation rules — `conf:` namespace

The W3C `.txml` uses placeholders in the `conf:` namespace. Map them as follows:

| .txml | Clojure |
|---|---|
| `conf:datamodel=""` | omit (we use lambda execution model + flat working memory) |
| `<conf:pass/>` | `(final {:id :pass})` |
| `<conf:fail/>` | `(final {:id :fail})` |
| `conf:targetpass=""` | `:target :pass` |
| `conf:targetfail=""` | `:target :fail` |
| `<conf:script>X</conf:script>` | `(script {:expr (fn [_ _] X)})` |
| `conf:expr="N"` | `:expr N` (literal value) |
| `conf:VarN` (variable) | use keyword `:VarN` in data-model paths |
| `conf:idVal="N=M"` in `<data>` | `(data {:id :N :expr M})` or `(data-model {} (data {:id :N :expr M}))` |
| `conf:eventName="X"` | `:event :X` |
| `conf:incrementID=""` (script) | `(script {:expr (fn [_ d] (update d :Var1 inc))})` — patterns vary; read carefully |
| `conf:idQuoteExpr` etc. | rare; consult source — these are ECMAScript-specific quoting helpers |

**Rule of thumb:** `conf:` attributes always either inject a hard-coded id (`pass`/`fail`), wrap raw expression text, or substitute a numeric/string literal. Read the comment at top of each .txml — it states the success/failure criterion in plain English.

## Element idioms

```clojure
(state {:id :s0}
  (on-entry {} (raise {:event :foo}))
  (transition {:event :foo :target :s1}))
```

For data-model bindings, see `com.fulcrologic.statecharts.elements/data-model` and `data`. Default location for `assign` is a vector path `[:Var1]`.

For tests that send delayed events: use `(Send {:event :foo :delay 100})`. The simple env uses `manually-polled-queue`; you may need `sp/receive-events!` to drain delayed events. See `runner/process-with-delays` (TBD — extend runner if needed).

## Critique workflow

If you (critique) get a failing-test handoff:

1. Read the .txml directly (the URL is in the briefing).
2. Determine: **(a)** translation error in the test, **(b)** ambiguity in the spec / unsupported feature, or **(c)** bug in the library.
3. **(a)** → Set task back to `pending` with a comment describing the fix and remove owner. A fresh implementer will pick it up.
4. **(b)** → Mark task with metadata `{:skip-reason "..."}`, set status to `completed` with a `^:irp/skip` marker on the test, and add to the skip log file `src/test/com/fulcrologic/statecharts/irp/_skipped.md`.
5. **(c)** → Add an entry to `src/test/com/fulcrologic/statecharts/irp/_library_issues.md` with: test #, .txml link, expected vs actual, suspected file/line in the library. Mark task `completed` with metadata `{:library-issue true}`. The coordinator (Tony) will fix later.

## Known pitfalls

- All event names in this library are keywords. `event="foo.bar"` becomes `:foo.bar` (a keyword with a dot — that's valid).
- `:initial` may be a keyword or vector.
- **IMPORTANT**: Wrap the entire chart inside a parent compound state so `:pass`/`:fail` finals are NOT siblings of `:ROOT`. A top-level final triggers `exit-interpreter!` which CLEARS the configuration before `start!` returns — `runner/passes?` would then never see `:pass`. Pattern:
  ```clojure
  (chart/statechart {:initial :_root}
    (state {:id :_root :initial :s0}
      (state {:id :s0} ...)
      (final {:id :pass})
      (final {:id :fail})))
  ```
- Some tests use `<send>` (capital `S` is the Clojure element). `<raise>` is `raise`.
- Tests using `<datamodel>` / `<data>` need the data element imported and a default location of `[:VarName]`.

## Coordination

- All progress in TaskList. Implementers claim by lowest task ID first.
- Library issues: append to `_library_issues.md`. Don't rabbit-hole — document and move on.
- Tests are orthogonal — share the single REPL on port from `.nrepl-port`.

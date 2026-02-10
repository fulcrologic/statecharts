# Spec: Documentation Improvements

**Status**: done
**Priority**: P2
**Created**: 2026-02-08
**Completed**: 2026-02-09
**Owner**: doc-fixer

## Context

Documentation audit identified several areas for improvement. The main documentation (Guide.adoc) is excellent (9/10) but has gaps in discoverability and practical guidance. Individual issues are listed below.

## Requirements

### Critical Fixes
. Fix typo in `docs/ai/clojure-library-source-and-documentation.md` line 44: `doc` should be `source`
. Fix typo in Guide.adoc line 94: "you simple want" -> "you simply want"
. Commit to stability in convenience namespace — remove ALPHA status. Don't make breaking changes.

## Affected Modules

- `docs/ai/clojure-library-source-and-documentation.md` - Bug fix
- `Guide.adoc` 
- Various source files - Docstring improvements

## Verification

. [x] AI docs bug fixed - `doc` -> `source` in clojure-library-source-and-documentation.md:44
. [x] Guide.adoc typo fixed - "you simple want" -> "you simply want" at line 94

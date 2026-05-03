# Skipped IRP Tests

Tests skipped because they exercise SCXML features this library does not implement, or are otherwise unportable.

Tests 147, 148, 149, 150, 151, 153, 155, 319 have been ported and now pass.
Tests 152 and 156 have been ported as real failing tests; see `_library_issues.md`.

- 173 — `<send targetExpr>` evaluation timing. Lambda execution model is lazy by construction. https://www.w3.org/Voice/2013/scxml-irp/173/test173.txml
- 201 — Requires BasicHTTP Event I/O Processor (optional in W3C IRP); not implemented in this library. https://www.w3.org/Voice/2013/scxml-irp/201/test201.txml
- 216 — Requires `<invoke srcexpr>` to load an external SCXML file at runtime. This library uses Clojure data, not XML; URL-based chart loading is out of scope. https://www.w3.org/Voice/2013/scxml-irp/216/test216.txml
- 189 — `<send target="#_internal">` routes to internal queue. Library lacks this special case. https://www.w3.org/Voice/2013/scxml-irp/189/test189.txml
- 277 — Illegal `<data>` expression leaves var unbound (ECMAScript-specific error semantics; lambda execution model n/a). https://www.w3.org/Voice/2013/scxml-irp/277/test277.txml
- 280 — Late binding (`binding="late"`); this library uses early binding only. https://www.w3.org/Voice/2013/scxml-irp/280/test280.txml
- 286 — Assignment to undeclared var raises error.execution; flat data model has no declared/undeclared distinction. https://www.w3.org/Voice/2013/scxml-irp/286/test286.txml
- 298 — donedata `<param>` referencing invalid location raises error.execution; flat data model has no declared/undeclared distinction. https://www.w3.org/Voice/2013/scxml-irp/298/test298.txml
- 302 — Top-level `<script>` runs at load time; this library has no top-level load-time script pathway. https://www.w3.org/Voice/2013/scxml-irp/302/test302.txml
- 322 — `_sessionid` reassignment must raise error.execution; this library does not protect system vars from reassignment. https://www.w3.org/Voice/2013/scxml-irp/322/test322.txml
- 324 — `_name` reassignment must raise error.execution; same reason as 322. https://www.w3.org/Voice/2013/scxml-irp/324/test324.txml
- 325 — `_ioprocessors` system variable; this library does not expose it. https://www.w3.org/Voice/2013/scxml-irp/325/test325.txml
- 326 — `_ioprocessors` reassignment must raise error.execution; library does not expose or protect it. https://www.w3.org/Voice/2013/scxml-irp/326/test326.txml
- 329 — All system vars must be read-only; library does not protect system variables. https://www.w3.org/Voice/2013/scxml-irp/329/test329.txml
- 336 — External `_event.origin` must be a routable target; library does not populate this. https://www.w3.org/Voice/2013/scxml-irp/336/test336.txml
- 343 — Illegal `<param>` location must raise error.execution; flat data model has no declared/undeclared distinction. https://www.w3.org/Voice/2013/scxml-irp/343/test343.txml
- 346 — System variable assignment must raise error.execution (duplicate scope of 329); library does not protect system variables. https://www.w3.org/Voice/2013/scxml-irp/346/test346.txml
- 349 — `_event.origin` can be used as a send target (round-trip). Library does not populate routable origin on external events (same as test 336). https://www.w3.org/Voice/2013/scxml-irp/349/test349.txml
- 350 — Session can send to itself via `#_scxml_<sessionid>` target. Library does not parse `#_scxml_<id>` target prefix (same family as 189/191/192). https://www.w3.org/Voice/2013/scxml-irp/350/test350.txml
- 343 — Illegal `<param>` location in donedata; flat data model has no undeclared-binding error. https://www.w3.org/Voice/2013/scxml-irp/343/test343.txml
- 346 — Any system variable assignment raises error.execution; library does not protect system vars. https://www.w3.org/Voice/2013/scxml-irp/346/test346.txml
- 351 — sendid is blank when no explicit id on send; library exposes auto-generated sendid (test 333 family). https://www.w3.org/Voice/2013/scxml-irp/351/test351.txml
- 352 — _event.origintype is the SCXML processor URL; library does not populate origintype (test 198 family). https://www.w3.org/Voice/2013/scxml-irp/352/test352.txml
- 444–460 — ECMAScript data model tests (datamodel="ecmascript"); library uses lambda execution model. https://www.w3.org/Voice/2013/scxml-irp/444/test444.txml (etc.)
- 488 — Illegal param expr in donedata raises error.execution before done event; ordering issue (test 343/312 family). https://www.w3.org/Voice/2013/scxml-irp/488/test488.txml
- 495 — SCXML I/O processor target="#_internal" routes to internal queue; #_internal not supported (test 189). https://www.w3.org/Voice/2013/scxml-irp/495/test495.txml
- 496 — Unreachable send target raises error.communication; library does not implement error.communication. https://www.w3.org/Voice/2013/scxml-irp/496/test496.txml
- 500 — _ioprocessors contains SCXML I/O processor location; library does not expose _ioprocessors. https://www.w3.org/Voice/2013/scxml-irp/500/test500.txml
- 501 — _ioprocessors location usable as send target; library does not expose _ioprocessors. https://www.w3.org/Voice/2013/scxml-irp/501/test501.txml
- 509–522 — Basic HTTP Event I/O Processor tests; BasicHTTP is optional in W3C IRP and not implemented. https://www.w3.org/Voice/2013/scxml-irp/509/test509.txml (etc.)
- 525 — foreach shallow-copy test; foreach not implemented. https://www.w3.org/Voice/2013/scxml-irp/525/test525.txml
- 528 — Illegal content expr in donedata raises error.execution before done event; ordering issue (test 312 family). https://www.w3.org/Voice/2013/scxml-irp/528/test528.txml
- 531–532, 534 — Basic HTTP / _scxmleventname parameter tests; BasicHTTP not implemented. https://www.w3.org/Voice/2013/scxml-irp/531/test531.txml (etc.)

## Intentionally unsupported — inline `<invoke><content>`

User policy 2026-05-02: inline-invoke is out of scope. The W3C `_parent`/`_invokeid`/`_internal` send-target gaps remain real bugs and stay in `_library_issues.md` (test 189 is `_internal` only — keep).

- 187 — Child-session termination cancels its pending delayed sends. Requires inline `<invoke><content>` + `#_parent` target + cancellation on session exit. https://www.w3.org/Voice/2013/scxml-irp/187/test187.txml
- 191 — Child invocation `<send target="#_parent">`. Requires inline `<invoke><content>` + `#_parent` routing. https://www.w3.org/Voice/2013/scxml-irp/191/test191.txml
- 192 — `<send target="#_<invokeid>">`. Requires inline `<invoke><content>` + `#_invokeid` routing. https://www.w3.org/Voice/2013/scxml-irp/192/test192.txml
- 207 — Inline `<invoke>` + `#_parent` + cross-session cancel. Blocked by inline-invoke. https://www.w3.org/Voice/2013/scxml-irp/207/test207.txml
- 215 — Inline `<invoke>` with `typeexpr` + `done.invoke`. https://www.w3.org/Voice/2013/scxml-irp/215/test215.txml
- 220 — Inline `<invoke>` + `done.invoke.<id>` delivery to parent. https://www.w3.org/Voice/2013/scxml-irp/220/test220.txml
- 223 — `<invoke idlocation>` — requires working invoked child session (inline-invoke). https://www.w3.org/Voice/2013/scxml-irp/223/test223.txml
- 224 — Autogenerated invokeid format (`<stateid>.<platformid>`). Requires inline child session. https://www.w3.org/Voice/2013/scxml-irp/224/test224.txml
- 225, 226, 228, 229, 232, 233, 234, 235, 236, 237, 239, 240, 241, 242, 243, 244, 245, 247, 252, 253 — Bulk invocation suite: inline `<invoke><content><scxml>` + `namelist`/`<param>`/`<finalize>`/`autoforward`/`done.invoke.<id>`/`#_parent`/`#_invokeid`. https://www.w3.org/Voice/2013/scxml-irp/225/test225.txml (etc.)
- 338 — `invokeid` in events from invoked process; requires inline invoke + `#_parent`. https://www.w3.org/Voice/2013/scxml-irp/338/test338.txml
- 347 — SCXML I/O processor bidirectional parent/child; requires inline invoke + `#_parent`/`#_child`. https://www.w3.org/Voice/2013/scxml-irp/347/test347.txml
- 422 — Invoke execution at macrostep end; requires inline invoke + `#_parent`. https://www.w3.org/Voice/2013/scxml-irp/422/test422.txml
- 530 — Invoke content evaluation timing; requires inline invoke. https://www.w3.org/Voice/2013/scxml-irp/530/test530.txml

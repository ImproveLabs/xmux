# Recorded cmux traces

`*.xmtrace` files are JSON lines. The first line is a header, every other line is one raw
`cmux.protocol/2` stream item exactly as the daemon sent it:

```
{"header":{"scenario":"styles","cmux":"cmux 0.1.0 (c858820...)","recorded":"2026-10-30T21:00:00Z"}}
{"t":0,"stream":"events","item":{"kind":"snapshot","snapshot":{...}}}
{"t":1012,"stream":"attach:term_...","item":{"kind":"snapshot","render":{...}}}
{"t":1045,"stream":"attach:term_...","item":{"kind":"patch","render":{...}}}
```

`t` is milliseconds since the recording started. `vpr bridge:record` (`xmux record`) records
them against a sandboxed cmux server (scratch home, `/bin/sh`, no dotfiles; the sandbox path is
replaced by `/sandbox`), one fresh workspace per scenario, and stores what a fresh attachment
reports at the end in `../expected/<name>.grid.json`. The bridge's conformance test replays every
trace through the bridge's state machines and writes the resulting frame stream to
`../frames/<name>.xmframes`; the Rust grid and the Kotlin `RowApplier` must both reach the
expected grids from it.

Scenarios: static80x24, scroll, styles, unicode, cursor, resize80to120, split2panes, tabs,
full_reset, burst.

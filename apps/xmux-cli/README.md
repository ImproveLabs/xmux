# xmux-cli

Developer CLI, built as `xmux`, for recording cmux traces, replaying terminal grids,
decoding frame streams, and probing live sessions.

Run through the workspace binary after `vp install`:

```sh
vpx _xmux --help
vpx _xmux replay path/to/trace.xmtrace
```

The launcher builds and runs the Rust binary through Cargo, forwarding arguments, input, output,
and the exit code. Relative file paths resolve from the caller's working directory.

Run the `xmux-cli` package directly with Cargo:

```sh
cargo run -p xmux-cli --bin xmux -- --help
```

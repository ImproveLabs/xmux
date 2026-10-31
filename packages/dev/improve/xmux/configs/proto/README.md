# proto

The repo's toolchain is pinned in `/.prototools` and installed by [proto](https://moonrepo.dev/proto):
Bun, Node, Rust and the JDK. Run `proto use` once in the repo (with `auto-install` on, tools also
install on first use). CI installs the same versions with `moonrepo/setup-toolchain`.

Some tools pin their version in their own file as well; `__tests__/toolchain-pins.test.ts` keeps the
pins in agreement:

| Tool | Also pinned in |
|---|---|
| Rust | `rust-toolchain.toml` (read by rustup and cargo) |
| Bun | `package.json` `packageManager` (read by Vite+) |

`bin/` resolves a pinned tool's path through `proto bin` (used by `_gradle` to set `JAVA_HOME` to
the pinned JDK).

Not managed by proto: Gradle (its wrapper pins the version) and the Android SDK (`ANDROID_HOME`, see
`../android`).

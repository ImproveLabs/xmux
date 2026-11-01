# xmux wire schema

`xmux.proto` defines messages exchanged between the bridge and clients, including pairing,
session topology, terminal updates, and input. Rust and Android builds generate their types
from this single schema.

- `android/`: Gradle module `:xmux-proto`, generating Java lite and Kotlin types.
- `rust/`: Rust crate `xmux-proto`, generating protobuf types and implementing wire framing.

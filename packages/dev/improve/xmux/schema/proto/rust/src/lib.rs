//! xmux wire protocol shared by the bridge, the headset client (via the same schema) and tools.
//!
//! A frame on the wire is a [`Header`] (16 bytes, little-endian) followed by one protobuf
//! `Frame` message (see `packages/dev/improve/xmux/schema/proto/xmux.proto`), optionally LZ4 block-compressed. The message types
//! are generated at build time by `build.rs` (protox + prost-build).

#![forbid(unsafe_code)]

#[allow(clippy::all, missing_docs)]
mod generated {
    include!(concat!(env!("OUT_DIR"), "/xmux.proto.rs"));
}

pub use generated::*;
pub use prost::Message;

pub mod checksum;
pub mod framing;

pub use framing::{
    FramingError, HEADER_LEN, Header, PROTOCOL_VERSION, decode_frame, encode_frame, payload_kind,
};

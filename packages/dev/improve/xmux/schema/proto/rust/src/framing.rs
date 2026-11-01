//! 16-byte frame header and LZ4 body handling.
//!
//! ```text
//! offset  size  field
//! 0       4     body_len   u32  length of the (possibly compressed) body that follows
//! 4       1     kind       u8   Frame.payload oneof field number (1 = hello, 4 = rows, ...)
//! 5       1     flags      u8   bit 0: body is LZ4 block-compressed (uncompressed_len follows)
//! 6       2     version    u16  PROTOCOL_VERSION
//! 8       4     id         u32  terminal or screen id the frame is about (0 if none)
//! 12      4     sequence   u32  per-connection monotonically increasing sequence
//! ```
//! When `flags & FLAG_LZ4` is set the body starts with a u32 uncompressed length followed by the
//! LZ4 block. The header lets a receiver route or drop frames without decoding the body.

use prost::Message;

use crate::{Frame, frame::Payload};

pub const HEADER_LEN: usize = 16;
pub const PROTOCOL_VERSION: u16 = 1;
pub const FLAG_LZ4: u8 = 0b0000_0001;
/// Bodies smaller than this are never compressed (LZ4 cannot help and the headset skips a copy).
pub const COMPRESS_THRESHOLD: usize = 256;
const KIND_MAX: u8 = 19;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub body_len: u32,
    pub kind: u8,
    pub flags: u8,
    pub version: u16,
    pub id: u32,
    pub sequence: u32,
}

#[derive(Debug, thiserror::Error)]
pub enum FramingError {
    #[error("buffer too short: need {need} bytes, have {have}")]
    Short { need: usize, have: usize },
    #[error("unsupported protocol version {0}")]
    Version(u16),
    #[error("unknown payload kind {0}")]
    Kind(u8),
    #[error("header kind {header} does not match payload kind {payload}")]
    KindMismatch { header: u8, payload: u8 },
    #[error("lz4: {0}")]
    Lz4(String),
    #[error("protobuf: {0}")]
    Decode(#[from] prost::DecodeError),
}

/// The `Frame.payload` oneof field number, or 0 when no payload is set.
pub fn payload_kind(frame: &Frame) -> u8 {
    match frame.payload {
        Some(Payload::Hello(_)) => 1,
        Some(Payload::Welcome(_)) => 2,
        Some(Payload::Topology(_)) => 3,
        Some(Payload::Rows(_)) => 4,
        Some(Payload::Cursor(_)) => 5,
        Some(Payload::AttachState(_)) => 6,
        Some(Payload::Input(_)) => 7,
        Some(Payload::Resize(_)) => 8,
        Some(Payload::FocusDirection(_)) => 9,
        Some(Payload::Checksum(_)) => 10,
        Some(Payload::Ping(_)) => 11,
        Some(Payload::Pong(_)) => 12,
        Some(Payload::Stats(_)) => 13,
        Some(Payload::AttachTerminal(_)) => 14,
        Some(Payload::DetachTerminal(_)) => 15,
        Some(Payload::ResyncTerminal(_)) => 16,
        Some(Payload::Bye(_)) => 17,
        Some(Payload::FocusPane(_)) => 18,
        Some(Payload::FocusTab(_)) => 19,
        None => 0,
    }
}

impl Header {
    pub fn write(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.body_len.to_le_bytes());
        out.push(self.kind);
        out.push(self.flags);
        out.extend_from_slice(&self.version.to_le_bytes());
        out.extend_from_slice(&self.id.to_le_bytes());
        out.extend_from_slice(&self.sequence.to_le_bytes());
    }

    pub fn parse(bytes: &[u8]) -> Result<Header, FramingError> {
        if bytes.len() < HEADER_LEN {
            return Err(FramingError::Short {
                need: HEADER_LEN,
                have: bytes.len(),
            });
        }
        let body_len = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        let kind = bytes[4];
        if kind == 0 || kind > KIND_MAX {
            return Err(FramingError::Kind(kind));
        }
        let flags = bytes[5];
        let version = u16::from_le_bytes([bytes[6], bytes[7]]);
        if version != PROTOCOL_VERSION {
            return Err(FramingError::Version(version));
        }
        let id = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]);
        let sequence = u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]);
        Ok(Header {
            body_len,
            kind,
            flags,
            version,
            id,
            sequence,
        })
    }
}

/// Serializes `frame` into a wire frame, compressing large bodies.
pub fn encode_frame(frame: &Frame, id: u32, sequence: u32) -> Vec<u8> {
    let bytes = frame.encode_to_vec();
    let compress = bytes.len() >= COMPRESS_THRESHOLD;
    let body: Vec<u8> = if compress {
        let mut body = Vec::with_capacity(4 + bytes.len() / 2);
        body.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        body.extend_from_slice(&lz4_flex::block::compress(&bytes));
        body
    } else {
        bytes
    };
    let header = Header {
        body_len: body.len() as u32,
        kind: payload_kind(frame),
        flags: if compress { FLAG_LZ4 } else { 0 },
        version: PROTOCOL_VERSION,
        id,
        sequence,
    };
    let mut out = Vec::with_capacity(HEADER_LEN + body.len());
    header.write(&mut out);
    out.extend_from_slice(&body);
    out
}

/// Parses one wire frame from the start of `bytes`; the number of bytes consumed is
/// `HEADER_LEN + header.body_len`.
pub fn decode_frame(bytes: &[u8]) -> Result<(Header, Frame), FramingError> {
    let header = Header::parse(bytes)?;
    let end = HEADER_LEN + header.body_len as usize;
    if bytes.len() < end {
        return Err(FramingError::Short {
            need: end,
            have: bytes.len(),
        });
    }
    let body = &bytes[HEADER_LEN..end];
    let frame = if header.flags & FLAG_LZ4 != 0 {
        if body.len() < 4 {
            return Err(FramingError::Short {
                need: 4,
                have: body.len(),
            });
        }
        let n = u32::from_le_bytes([body[0], body[1], body[2], body[3]]) as usize;
        let raw = lz4_flex::block::decompress(&body[4..], n)
            .map_err(|e| FramingError::Lz4(e.to_string()))?;
        Frame::decode(raw.as_slice())?
    } else {
        Frame::decode(body)?
    };
    let payload = payload_kind(&frame);
    if payload != header.kind {
        return Err(FramingError::KindMismatch {
            header: header.kind,
            payload,
        });
    }
    Ok((header, frame))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Row, Rows, Run, Underline};

    /// Same content as the Kotlin FrameCodecTest's `rowsFrame(fill)`.
    fn rows_frame(fill: usize) -> Frame {
        Frame {
            payload: Some(Payload::Rows(Rows {
                terminal: 7,
                generation: 2,
                sequence: 99,
                cols: 80,
                rows: 24,
                dirty: vec![Row {
                    index: 3,
                    runs: vec![Run {
                        text: "x".repeat(fill),
                        fg: 0xFFD4D4D4,
                        bg: 0,
                        attrs: 1,
                        underline: Underline::Single as i32,
                        width_hint: 0,
                    }],
                }],
                full_reset: false,
                ..Default::default()
            })),
        }
    }

    #[test]
    fn small_frame_is_not_compressed_and_round_trips() {
        let frame = rows_frame(3);
        let wire = encode_frame(&frame, 7, 99);
        assert_eq!(wire.len(), HEADER_LEN + frame.encode_to_vec().len());
        let (h, decoded) = decode_frame(&wire).unwrap();
        assert_eq!(h.flags, 0);
        assert_eq!(h.kind, 4);
        assert_eq!((h.id, h.sequence), (7, 99));
        assert_eq!(decoded, frame);
        let Some(Payload::Rows(rows)) = decoded.payload else {
            panic!("rows")
        };
        assert_eq!(rows.dirty[0].runs[0].text, "xxx");
        assert_eq!(rows.dirty[0].runs[0].underline(), Underline::Single);
    }

    #[test]
    fn large_frame_is_lz4_compressed_and_round_trips() {
        let frame = rows_frame(4000);
        let wire = encode_frame(&frame, 1, 2);
        assert!(wire.len() < 4000 / 2, "lz4 should shrink repetitive text");
        let (h, decoded) = decode_frame(&wire).unwrap();
        assert_eq!(h.flags & FLAG_LZ4, FLAG_LZ4);
        assert_eq!(decoded, frame);
    }

    #[test]
    fn rejects_bad_version_kind_truncation_and_mismatch() {
        let frame = rows_frame(3);
        let mut wire = encode_frame(&frame, 1, 2);
        assert!(matches!(
            decode_frame(&wire[..10]),
            Err(FramingError::Short { .. })
        ));
        assert!(matches!(
            decode_frame(&wire[..wire.len() - 1]),
            Err(FramingError::Short { .. })
        ));
        wire[6] = 9;
        assert!(matches!(decode_frame(&wire), Err(FramingError::Version(9))));
        wire[6] = PROTOCOL_VERSION as u8;
        wire[4] = 200;
        assert!(matches!(decode_frame(&wire), Err(FramingError::Kind(200))));
        wire[4] = 5; // claims Cursor, body is Rows
        assert!(matches!(
            decode_frame(&wire),
            Err(FramingError::KindMismatch {
                header: 5,
                payload: 4
            })
        ));
    }

    /// Writes the cross-language goldens consumed by the Kotlin FrameCodecTest.
    #[test]
    fn golden_rows_frames() {
        for (name, fill, seq) in [("rows-small.bin", 3, 99), ("rows-lz4.bin", 4000, 100)] {
            let wire = encode_frame(&rows_frame(fill), 7, seq);
            let path = format!(
                "{}/packages/dev/improve/xmux/testing/fixtures/frames/{name}",
                env!("XMUX_REPO_ROOT")
            );
            if std::env::var("XMUX_UPDATE_GOLDENS").is_ok() {
                std::fs::write(&path, &wire).unwrap();
            }
            let golden = std::fs::read(&path)
                .unwrap_or_else(|_| panic!("{name}: run with XMUX_UPDATE_GOLDENS=1 to create"));
            assert_eq!(
                golden, wire,
                "{name}: wire bytes changed; update the golden deliberately"
            );
        }
    }
}

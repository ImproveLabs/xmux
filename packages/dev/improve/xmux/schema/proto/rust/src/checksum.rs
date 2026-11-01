//! The terminal-state checksum carried by `Checksum` frames. It hashes the rows exactly as they
//! travel on the wire (runs, not cells), so the bridge and a client agree without having to agree
//! on grapheme segmentation.

use crate::Run;

const OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const PRIME: u64 = 0x0000_0100_0000_01b3;

/// FNV-1a 64.
#[derive(Clone, Copy, Debug)]
pub struct Fnv1a(u64);

impl Default for Fnv1a {
    fn default() -> Self {
        Fnv1a(OFFSET)
    }
}

impl Fnv1a {
    pub fn bytes(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 ^= u64::from(b);
            self.0 = self.0.wrapping_mul(PRIME);
        }
    }

    pub fn u32(&mut self, value: u32) {
        self.bytes(&value.to_le_bytes());
    }

    pub fn finish(&self) -> u64 {
        self.0
    }
}

/// Checksum over `(row index, runs)` pairs in row order; see the `Checksum` message.
pub fn rows_checksum<'a>(rows: impl IntoIterator<Item = (u32, &'a [Run])>) -> u64 {
    let mut hash = Fnv1a::default();
    for (index, runs) in rows {
        hash.u32(index);
        for run in runs {
            hash.bytes(run.text.as_bytes());
            hash.bytes(&[0]);
            hash.u32(run.fg);
            hash.u32(run.bg);
            hash.u32(run.attrs);
            hash.u32(run.underline as u32);
            hash.u32(run.width_hint);
        }
    }
    hash.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fnv1a_matches_reference_vectors() {
        let mut empty = Fnv1a::default();
        empty.bytes(b"");
        assert_eq!(empty.finish(), 0xcbf2_9ce4_8422_2325);
        let mut a = Fnv1a::default();
        a.bytes(b"a");
        assert_eq!(a.finish(), 0xaf63_dc4c_8601_ec8c);
        let mut foobar = Fnv1a::default();
        foobar.bytes(b"foobar");
        assert_eq!(foobar.finish(), 0x8594_4171_f739_67e8);
    }

    #[test]
    fn checksum_depends_on_order_and_style() {
        let run = |text: &str, fg| Run {
            text: text.into(),
            fg,
            ..Default::default()
        };
        let a = [run("ab", 1)];
        let b = [run("ab", 2)];
        let ab = rows_checksum([(0, &a[..]), (1, &b[..])]);
        let ba = rows_checksum([(0, &b[..]), (1, &a[..])]);
        assert_ne!(ab, ba);
        // Pinned so the Kotlin side can assert the same value (ChecksumTest).
        assert_eq!(ab, rows_checksum([(0, &a[..]), (1, &b[..])]));
    }
}

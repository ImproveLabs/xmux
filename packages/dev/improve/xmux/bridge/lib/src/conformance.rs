//! Trace replay for the conformance fixtures: a trace goes through the same state machines the
//! bridge serves with, the resulting frames are applied to client grids, and the grids must
//! equal what cmux itself reported at the end of the recording (`expected/<name>.grid.json`).
//! The frame stream is also written as `frames/<name>.xmframes`, which the Kotlin client replays
//! against the same expected grids.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use xmux_proto::frame::Payload;
use xmux_proto::{Frame, FramingError, HEADER_LEN, Header, Topology, decode_frame, encode_frame};

use crate::grid::{Applied, ExpectedGrid, Grid};
use crate::terminal::TerminalState;
use crate::topology::TopologyState;
use crate::trace::Trace;

/// `packages/dev/improve/xmux/testing/fixtures`, found by walking up from this crate.
pub fn fixtures_dir() -> PathBuf {
    let rel = Path::new("packages/dev/improve/xmux/testing/fixtures");
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .map(|dir| dir.join(rel))
        .find(|dir| dir.is_dir())
        .expect("testing/fixtures not found above the xmux-bridge crate")
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExpectedTerminal {
    pub cmux_id: String,
    pub grid: ExpectedGrid,
}

/// `expected/<name>.grid.json`: one entry per attach stream, in trace order. In the frame
/// stream terminal `i + 1` is `terminals[i]`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExpectedFile {
    pub scenario: String,
    pub terminals: Vec<ExpectedTerminal>,
}

pub struct Replayed {
    pub terminals: Vec<ExpectedTerminal>,
    /// The concatenated wire frames, as `frames/<name>.xmframes`.
    pub stream: Vec<u8>,
    pub topology: Option<Topology>,
}

/// Replays every item of `trace` in timestamp order (session events first on ties), flushing
/// after each item the way the bridge flushes after each batch, and ends with a checksum per
/// terminal.
pub fn replay(trace: &Trace) -> Replayed {
    enum Item<'a> {
        Event(&'a crate::model::SessionItem),
        Render(usize, &'a crate::model::RenderItem),
    }
    let mut items: Vec<(u64, usize, Item)> = Vec::new();
    for (t, e) in &trace.events {
        items.push((*t, 0, Item::Event(e)));
    }
    for (i, a) in trace.attaches.iter().enumerate() {
        for (t, r) in &a.items {
            items.push((*t, i + 1, Item::Render(i, r)));
        }
    }
    items.sort_by_key(|(t, order, _)| (*t, *order));

    let mut topology = TopologyState::new();
    let mut seen_events = false;
    let mut terminals: Vec<TerminalState> = (0..trace.attaches.len())
        .map(|i| TerminalState::new(i as u32 + 1))
        .collect();
    let mut grids: Vec<Grid> = (0..trace.attaches.len()).map(|_| Grid::new()).collect();
    let mut stream = Vec::new();
    let emit = |frame: &Frame, grids: &mut [Grid], stream: &mut Vec<u8>| {
        let (id, seq) = frame_route(frame);
        stream.extend(encode_frame(frame, id, seq));
        if id > 0 {
            let applied = grids[id as usize - 1].apply(frame);
            assert_ne!(
                applied,
                Applied::NeedsSnapshot,
                "terminal {id} lost sync during replay"
            );
        }
    };
    for (_, _, item) in items {
        match item {
            Item::Event(e) => {
                seen_events = true;
                if topology.apply(e) {
                    emit(&topology.frame(), &mut grids, &mut stream);
                }
            }
            Item::Render(i, r) => {
                terminals[i].apply(r);
                for frame in terminals[i].take_frames() {
                    emit(&frame, &mut grids, &mut stream);
                }
            }
        }
    }
    for t in &terminals {
        if t.is_ready() {
            emit(&t.checksum_frame(), &mut grids, &mut stream);
        }
    }
    Replayed {
        terminals: trace
            .attaches
            .iter()
            .zip(&grids)
            .map(|(a, g)| ExpectedTerminal {
                cmux_id: a.terminal.clone(),
                grid: g.expected(),
            })
            .collect(),
        stream,
        topology: seen_events.then(|| topology.topology()),
    }
}

/// Header `id` and `sequence` for a frame: the terminal id and the rows sequence for terminal
/// frames, zero otherwise.
pub fn frame_route(frame: &Frame) -> (u32, u32) {
    match &frame.payload {
        Some(Payload::Rows(r)) => (r.terminal, r.sequence),
        Some(Payload::Cursor(c)) => (c.terminal, 0),
        Some(Payload::Checksum(c)) => (c.terminal, c.sequence),
        Some(Payload::AttachState(a)) => (a.terminal, 0),
        _ => (0, 0),
    }
}

/// Splits concatenated wire frames.
pub fn decode_stream(mut bytes: &[u8]) -> Result<Vec<(Header, Frame)>, FramingError> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let (header, frame) = decode_frame(bytes)?;
        bytes = &bytes[HEADER_LEN + header.body_len as usize..];
        out.push((header, frame));
    }
    Ok(out)
}

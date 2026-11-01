//! Trace files (`*.xmtrace`): what cmux sent, item by item, with timestamps. JSON lines; the
//! first line is a header, every other line one item:
//!
//! ```text
//! {"header":{"scenario":"styles","cmux":"0.1.0 (c858820)","recorded":"2026-10-30T12:00:00Z"}}
//! {"t":0,"stream":"events","item":{"kind":"snapshot","session":{...}}}
//! {"t":12,"stream":"attach:term_...","item":{"kind":"snapshot","size":{...},...}}
//! ```
//!
//! `t` is milliseconds since recording started. `item` is the raw cmux.protocol/2 stream item,
//! exactly as the daemon sent it; reading decodes it with the same lenient functions the live
//! bridge uses (`protocol`). Items the bridge does not use are skipped on read.

use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::Path;
use std::sync::Mutex;
use std::time::Instant;

use serde::{Deserialize, Serialize};

use crate::model::{RenderItem, SessionItem};
use crate::protocol::{decode_render, decode_session};

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TraceHeader {
    pub scenario: String,
    /// `cmux --version` of the daemon that produced the trace.
    pub cmux: String,
    pub recorded: String,
}

#[derive(Serialize, Deserialize)]
struct HeaderLine {
    header: TraceHeader,
}

#[derive(Serialize, Deserialize)]
struct ItemLine {
    t: u64,
    stream: String,
    item: serde_json::Value,
}

/// One terminal's attach stream inside a trace.
#[derive(Clone, Debug, PartialEq)]
pub struct AttachTrace {
    pub terminal: String,
    pub items: Vec<(u64, RenderItem)>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Trace {
    pub header: TraceHeader,
    pub events: Vec<(u64, SessionItem)>,
    /// In order of first appearance.
    pub attaches: Vec<AttachTrace>,
}

#[derive(Debug, thiserror::Error)]
pub enum TraceError {
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("line {line}: {source}")]
    Json {
        line: usize,
        source: serde_json::Error,
    },
    #[error("line {line}: unknown stream {stream:?}")]
    Stream { line: usize, stream: String },
    #[error("the first line must be a header")]
    MissingHeader,
}

impl Trace {
    pub fn read(path: &Path) -> Result<Trace, TraceError> {
        Trace::parse(BufReader::new(File::open(path)?))
    }

    pub fn parse(reader: impl BufRead) -> Result<Trace, TraceError> {
        let mut lines = reader.lines().enumerate();
        let header = match lines.next() {
            Some((_, line)) => {
                serde_json::from_str::<HeaderLine>(&line?)
                    .map_err(|_| TraceError::MissingHeader)?
                    .header
            }
            None => return Err(TraceError::MissingHeader),
        };
        let mut trace = Trace {
            header,
            events: Vec::new(),
            attaches: Vec::new(),
        };
        for (i, line) in lines {
            let line = line?;
            if line.trim().is_empty() {
                continue;
            }
            let json = |source| TraceError::Json {
                line: i + 1,
                source,
            };
            let item: ItemLine = serde_json::from_str(&line).map_err(json)?;
            if item.stream == "events" {
                if let Some(event) = decode_session(&item.item) {
                    trace.events.push((item.t, event));
                }
            } else if let Some(terminal) = item.stream.strip_prefix("attach:") {
                let Some(render) = decode_render(&item.item) else {
                    continue;
                };
                match trace.attaches.iter_mut().find(|a| a.terminal == terminal) {
                    Some(a) => a.items.push((item.t, render)),
                    None => trace.attaches.push(AttachTrace {
                        terminal: terminal.to_string(),
                        items: vec![(item.t, render)],
                    }),
                }
            } else {
                return Err(TraceError::Stream {
                    line: i + 1,
                    stream: item.stream,
                });
            }
        }
        Ok(trace)
    }

    pub fn attach(&self, terminal: &str) -> Option<&AttachTrace> {
        self.attaches.iter().find(|a| a.terminal == terminal)
    }
}

/// Appends items to a trace file from any thread.
pub struct TraceWriter {
    out: Mutex<BufWriter<File>>,
    start: Instant,
}

impl TraceWriter {
    pub fn create(path: &Path, header: &TraceHeader) -> Result<TraceWriter, TraceError> {
        let mut out = BufWriter::new(File::create(path)?);
        serde_json::to_writer(
            &mut out,
            &HeaderLine {
                header: header.clone(),
            },
        )
        .map_err(|source| TraceError::Json { line: 1, source })?;
        out.write_all(b"\n")?;
        Ok(TraceWriter {
            out: Mutex::new(out),
            start: Instant::now(),
        })
    }

    /// Appends a raw `session.events` item.
    pub fn events(&self, item: &serde_json::Value) -> Result<(), TraceError> {
        self.write("events".to_string(), item.clone())
    }

    /// Appends a raw `terminal.attach` item.
    pub fn attach(&self, terminal: &str, item: &serde_json::Value) -> Result<(), TraceError> {
        self.write(format!("attach:{terminal}"), item.clone())
    }

    fn write(&self, stream: String, item: serde_json::Value) -> Result<(), TraceError> {
        let line = ItemLine {
            t: self.start.elapsed().as_millis() as u64,
            stream,
            item,
        };
        let mut out = self.out.lock().unwrap();
        serde_json::to_writer(&mut *out, &line)
            .map_err(|source| TraceError::Json { line: 0, source })?;
        out.write_all(b"\n")?;
        Ok(())
    }

    pub fn finish(self) -> Result<(), TraceError> {
        self.out.into_inner().unwrap().flush()?;
        Ok(())
    }
}

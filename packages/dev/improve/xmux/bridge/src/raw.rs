//! A minimal blocking client for cmux.protocol/2 streams over the session's Unix socket. Each
//! stream owns its connection, as the protocol allows; closing the connection ends the stream
//! and drops its viewer lease. Mutations go through the cmux SDK instead.

use std::io::{BufRead, BufReader, ErrorKind, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::time::Duration;

use serde_json::{Map, Value, json};

use crate::backend::{BackendError, BackendResult, Poll};

const PROTOCOL: &str = "cmux.protocol/2";

pub struct RawStream {
    reader: BufReader<UnixStream>,
    writer: UnixStream,
    partial: Vec<u8>,
    /// Stream items read while waiting for a control response.
    pending: std::collections::VecDeque<Value>,
    lease: Option<String>,
    /// The selectors the stream was opened with; control requests repeat them.
    selectors: Map<String, Value>,
    next_request: u64,
    ended: bool,
}

fn err(message: impl Into<String>) -> BackendError {
    BackendError::Cmux(message.into())
}

fn random_hex() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    let mut out = String::new();
    for _ in 0..2 {
        let mut h = RandomState::new().build_hasher();
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
        );
        out.push_str(&format!("{:016x}", h.finish()));
    }
    out
}

impl RawStream {
    /// Opens `operation` (`session.events`, `terminal.attach`) with `params` merged into the
    /// current machine and session selectors.
    pub fn open(
        socket: &Path,
        operation: &str,
        params: Map<String, Value>,
    ) -> BackendResult<RawStream> {
        let writer = UnixStream::connect(socket)
            .map_err(|e| err(format!("connect {}: {e}", socket.display())))?;
        writer.set_read_timeout(Some(Duration::from_secs(10))).ok();
        let reader = BufReader::new(writer.try_clone().map_err(|e| err(e.to_string()))?);
        let mut stream = RawStream {
            reader,
            writer,
            partial: Vec::new(),
            pending: Default::default(),
            lease: None,
            selectors: Map::new(),
            next_request: 1,
            ended: false,
        };
        let mut all = Map::new();
        all.insert("machine".into(), json!("current"));
        all.insert("session".into(), json!("current"));
        all.insert(
            "stream_id".into(),
            json!(format!("stream_{}", random_hex())),
        );
        all.extend(params);
        stream.selectors = all
            .iter()
            .filter(|(k, _)| *k != "stream_id")
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let id = stream.send(operation, Value::Object(all))?;
        loop {
            let line = stream
                .read_line(Duration::from_secs(10))?
                .ok_or_else(|| err("no response"))?;
            if line.get("type").and_then(Value::as_str) == Some("response")
                && line.get("id").and_then(Value::as_str) == Some(id.as_str())
            {
                if line.get("ok") != Some(&Value::Bool(true)) {
                    let message = line
                        .pointer("/error/message")
                        .and_then(Value::as_str)
                        .unwrap_or("rejected");
                    return Err(err(format!("{operation}: {message}")));
                }
                stream.lease = line
                    .pointer("/result/attachment_lease")
                    .and_then(Value::as_str)
                    .map(str::to_string);
                return Ok(stream);
            }
        }
    }

    pub fn lease(&self) -> Option<&str> {
        self.lease.as_deref()
    }

    fn send(&mut self, operation: &str, params: Value) -> BackendResult<String> {
        let id = self.next_request.to_string();
        self.next_request += 1;
        let envelope = json!({
            "protocol": PROTOCOL, "type": "request", "id": id, "operation": operation, "params": params,
        });
        let mut line = serde_json::to_vec(&envelope).map_err(|e| err(e.to_string()))?;
        line.push(b'\n');
        self.writer
            .write_all(&line)
            .map_err(|e| err(e.to_string()))?;
        Ok(id)
    }

    /// Sends a connection-control request on this stream's connection (viewer resize/release)
    /// and waits for its response; stream items that arrive meanwhile are kept for `poll`.
    pub fn control(&mut self, operation: &str, params: Value) -> BackendResult<Value> {
        let mut all = self.selectors.clone();
        if let Value::Object(extra) = params {
            all.extend(extra);
        }
        let id = self.send(operation, Value::Object(all))?;
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            if left.is_zero() {
                return Err(err(format!("{operation}: no response")));
            }
            let Some(mut line) = self.read_line(left)? else {
                continue;
            };
            match line.get("type").and_then(Value::as_str) {
                Some("response") if line.get("id").and_then(Value::as_str) == Some(id.as_str()) => {
                    if line.get("ok") != Some(&Value::Bool(true)) {
                        let message = line
                            .pointer("/error/message")
                            .and_then(Value::as_str)
                            .unwrap_or("rejected");
                        return Err(err(format!("{operation}: {message}")));
                    }
                    return Ok(line
                        .get_mut("result")
                        .map(Value::take)
                        .unwrap_or(Value::Null));
                }
                Some("stream_item") | Some("stream_end") => self.pending.push_back(line),
                _ => {}
            }
        }
    }

    /// The next JSON line, or `None` on timeout. A partial line survives a timeout.
    fn read_line(&mut self, timeout: Duration) -> BackendResult<Option<Value>> {
        self.reader
            .get_ref()
            .set_read_timeout(Some(timeout.max(Duration::from_millis(1))))
            .ok();
        match self.reader.read_until(b'\n', &mut self.partial) {
            Ok(0) => Err(err("connection closed")),
            Ok(_) if self.partial.ends_with(b"\n") => {
                let line = std::mem::take(&mut self.partial);
                serde_json::from_slice(&line)
                    .map(Some)
                    .map_err(|e| err(format!("bad line: {e}")))
            }
            Ok(_) => Ok(None),
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => Ok(None),
            Err(e) => Err(err(e.to_string())),
        }
    }

    /// The next stream item (the envelope's `item`).
    pub fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<Value>> {
        if self.ended {
            return Ok(Poll::End {
                reason: "ended".into(),
            });
        }
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            let next = match self.pending.pop_front() {
                Some(line) => Some(line),
                None => self.read_line(left)?,
            };
            let Some(mut line) = next else {
                return Ok(Poll::TimedOut);
            };
            match line.get("type").and_then(Value::as_str) {
                Some("stream_item") => {
                    if let Some(item) = line.get_mut("item").map(Value::take) {
                        return Ok(Poll::Item(item));
                    }
                }
                Some("stream_end") => {
                    self.ended = true;
                    let reason = line
                        .get("reason")
                        .and_then(Value::as_str)
                        .unwrap_or("ended")
                        .to_string();
                    return Ok(Poll::End { reason });
                }
                _ => {}
            }
            if std::time::Instant::now() >= deadline {
                return Ok(Poll::TimedOut);
            }
        }
    }

    pub fn close(&mut self) {
        self.ended = true;
        let _ = self.writer.shutdown(std::net::Shutdown::Both);
    }
}

impl Drop for RawStream {
    fn drop(&mut self) {
        self.close();
    }
}

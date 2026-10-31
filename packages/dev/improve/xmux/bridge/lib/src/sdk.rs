//! [`Cmux`] for a live cmux-tui session over its local socket: streams through [`RawStream`]
//! (decoded leniently, see `protocol`), input and focus through the cmux Rust SDK.

use std::path::{Path, PathBuf};
use std::time::Duration;

use cmux::{
    Client, Config, InputModifier, MutationOptions, TerminalId, TerminalKeysOptions,
    TerminalMouseKind, TerminalMouseOptions,
};
use serde_json::{Map, Value, json};

use crate::backend::{
    Attachment, BackendError, BackendResult, Cmux, Direction, ItemStream, Modifier, MouseButton,
    MouseInput, MouseKind, Poll,
};
use crate::model::{RenderItem, SessionItem, Size};
use crate::protocol::{decode_render, decode_session};
use crate::raw::RawStream;
use crate::topology::PanePath;

fn err(e: impl std::fmt::Display) -> BackendError {
    BackendError::Cmux(e.to_string())
}

pub struct SdkCmux {
    client: Client,
    socket: PathBuf,
    session_name: String,
}

/// The socket path of a named local session, as cmux derives it from the environment.
pub fn socket_for_session(session: &str) -> BackendResult<PathBuf> {
    Ok(Config::try_from_env_or_default_session(session)
        .map_err(err)?
        .socket_path)
}

impl SdkCmux {
    /// Connects to a named local session (`main` is cmux's default), or to an explicit socket.
    pub fn connect(session: &str, socket: Option<&std::path::Path>) -> BackendResult<SdkCmux> {
        let config = match socket {
            Some(path) => Config::from_socket_path(path),
            None => Config::try_from_env_or_default_session(session).map_err(err)?,
        };
        let socket = config.socket_path.clone();
        let client = Client::connect(config).map_err(err)?;
        let session_name = client
            .current_session()
            .refresh()
            .ok()
            .and_then(|s| s.name)
            .unwrap_or_else(|| session.to_string());
        Ok(SdkCmux {
            client,
            socket,
            session_name,
        })
    }

    pub fn client(&self) -> &Client {
        &self.client
    }

    pub fn socket(&self) -> &Path {
        &self.socket
    }

    /// Opens a raw stream on this session's socket, for callers that keep the raw items
    /// (the trace recorder).
    pub fn raw_stream(
        &self,
        operation: &str,
        params: Map<String, Value>,
    ) -> BackendResult<RawStream> {
        RawStream::open(&self.socket, operation, params)
    }

    fn terminal(&self, id: &str) -> BackendResult<cmux::Terminal> {
        let id =
            TerminalId::parse(id).map_err(|_| BackendError::UnknownTerminal(id.to_string()))?;
        Ok(self.client.current_session().terminal(id))
    }
}

fn mutation(key: &str) -> BackendResult<MutationOptions> {
    MutationOptions::new(key).map_err(err)
}

impl Cmux for SdkCmux {
    fn session_name(&self) -> String {
        self.session_name.clone()
    }

    fn events(&self) -> BackendResult<Box<dyn ItemStream<SessionItem>>> {
        Ok(Box::new(RawEvents(
            self.raw_stream("session.events", Map::new())?,
        )))
    }

    fn attach(&self, terminal: &str) -> BackendResult<Box<dyn Attachment>> {
        // No size: this viewer never reports a grid, so it never resizes the Mac's terminal.
        let mut params = Map::new();
        params.insert("terminal".into(), json!(terminal));
        let stream = self
            .raw_stream("terminal.attach", params)
            .map_err(|e| match e {
                BackendError::Cmux(m) if m.contains("not found") => {
                    BackendError::UnknownTerminal(terminal.into())
                }
                other => other,
            })?;
        Ok(Box::new(RawAttachment(stream)))
    }

    fn write(&self, terminal: &str, text: &str, key: &str) -> BackendResult<()> {
        self.terminal(terminal)?
            .write_text_with(text, mutation(key)?)
            .map_err(err)?;
        Ok(())
    }

    fn keys(&self, terminal: &str, keys: &[String], key: &str) -> BackendResult<()> {
        self.terminal(terminal)?
            .keys_with(
                TerminalKeysOptions {
                    keys: keys.to_vec(),
                },
                mutation(key)?,
            )
            .map_err(err)?;
        Ok(())
    }

    fn mouse(&self, terminal: &str, m: &MouseInput, key: &str) -> BackendResult<()> {
        let options = TerminalMouseOptions {
            kind: match m.kind {
                MouseKind::Down => TerminalMouseKind::Down,
                MouseKind::Up => TerminalMouseKind::Up,
                MouseKind::Move => TerminalMouseKind::Move,
                MouseKind::Wheel => TerminalMouseKind::Wheel,
            },
            row: m.row,
            column: m.col,
            button: m.button.map(|b| match b {
                MouseButton::Left => cmux::MouseButton::Left,
                MouseButton::Middle => cmux::MouseButton::Middle,
                MouseButton::Right => cmux::MouseButton::Right,
            }),
            delta_rows: m.delta_rows,
            modifiers: m
                .modifiers
                .iter()
                .map(|m| match m {
                    Modifier::Shift => InputModifier::Shift,
                    Modifier::Control => InputModifier::Control,
                    Modifier::Alt => InputModifier::Alt,
                    Modifier::Meta => InputModifier::Meta,
                })
                .collect(),
        };
        self.terminal(terminal)?
            .mouse_with(options, mutation(key)?)
            .map_err(err)?;
        Ok(())
    }

    fn focus_direction(
        &self,
        pane: &PanePath,
        direction: Direction,
        key: &str,
    ) -> BackendResult<()> {
        let pane = self.pane(pane)?;
        let direction = match direction {
            Direction::Left => cmux::Direction::Left,
            Direction::Right => cmux::Direction::Right,
            Direction::Up => cmux::Direction::Up,
            Direction::Down => cmux::Direction::Down,
        };
        pane.focus_direction_with(direction, mutation(key)?)
            .map_err(err)?;
        Ok(())
    }

    fn focus_pane(&self, pane: &PanePath, key: &str) -> BackendResult<()> {
        self.pane(pane)?.focus_with(mutation(key)?).map_err(err)?;
        Ok(())
    }

    fn focus_tab(&self, pane: &PanePath, tab: &str, key: &str) -> BackendResult<()> {
        let tab = cmux::TabId::parse(tab).map_err(err)?;
        self.pane(pane)?
            .tab(tab)
            .focus_with(mutation(key)?)
            .map_err(err)?;
        Ok(())
    }
}

impl SdkCmux {
    fn pane(&self, path: &PanePath) -> BackendResult<cmux::Pane> {
        let session = self.client.current_session();
        Ok(session
            .workspace(cmux::WorkspaceId::parse(path.workspace.clone()).map_err(err)?)
            .screen(cmux::ScreenId::parse(path.screen.clone()).map_err(err)?)
            .pane(cmux::PaneId::parse(path.pane.clone()).map_err(err)?))
    }
}

/// A raw `session.events` stream decoded with [`decode_session`].
struct RawEvents(RawStream);

impl ItemStream<SessionItem> for RawEvents {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<SessionItem>> {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            match self.0.poll(left)? {
                Poll::Item(raw) => {
                    if let Some(item) = decode_session(&raw) {
                        return Ok(Poll::Item(item));
                    }
                }
                Poll::TimedOut => return Ok(Poll::TimedOut),
                Poll::End { reason } => return Ok(Poll::End { reason }),
            }
        }
    }

    fn cancel(&mut self) {
        self.0.close();
    }
}

/// A raw `terminal.attach` stream decoded with [`decode_render`], plus its viewer lease.
struct RawAttachment(RawStream);

impl ItemStream<RenderItem> for RawAttachment {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<RenderItem>> {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            match self.0.poll(left)? {
                Poll::Item(raw) => {
                    if let Some(item) = decode_render(&raw) {
                        return Ok(Poll::Item(item));
                    }
                }
                Poll::TimedOut => return Ok(Poll::TimedOut),
                Poll::End { reason } => return Ok(Poll::End { reason }),
            }
        }
    }

    fn cancel(&mut self) {
        self.0.close();
    }
}

impl Attachment for RawAttachment {
    fn resize(&mut self, size: Size) -> BackendResult<()> {
        let lease = self
            .0
            .lease()
            .ok_or_else(|| err("attachment has no viewer lease"))?
            .to_string();
        self.0
            .control(
                "terminal.viewer.resize",
                json!({"attachment_lease": lease, "cols": size.cols, "rows": size.rows}),
            )
            .map(drop)
    }

    fn release(&mut self) -> BackendResult<()> {
        let lease = self
            .0
            .lease()
            .ok_or_else(|| err("attachment has no viewer lease"))?
            .to_string();
        self.0
            .control(
                "terminal.viewer.release",
                json!({"attachment_lease": lease}),
            )
            .map(drop)
    }
}

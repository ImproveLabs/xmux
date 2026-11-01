//! The cmux operations the bridge needs, behind traits so the hub runs the same against the live
//! daemon (`sdk::SdkCmux`), a recorded trace (`replay::ReplayCmux`) and test fakes.
//!
//! The calls are blocking, like the cmux SDK: the hub runs each stream on its own thread and each
//! mutation on the blocking pool.

use std::time::Duration;

use crate::model::{RenderItem, SessionItem, Size};
use crate::topology::PanePath;

#[derive(Debug, thiserror::Error)]
pub enum BackendError {
    #[error("{0}")]
    Cmux(String),
    #[error("unknown terminal {0}")]
    UnknownTerminal(String),
}

pub type BackendResult<T> = Result<T, BackendError>;

#[derive(Debug, PartialEq)]
pub enum Poll<T> {
    Item(T),
    TimedOut,
    /// The stream ended; `reason` says why (`gap`, `canceled`, a closed terminal, ...).
    End {
        reason: String,
    },
}

pub trait ItemStream<T>: Send {
    /// Waits at most `timeout` for the next item.
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<T>>;
    fn cancel(&mut self);
}

/// A `terminal.attach` stream plus its viewer lease.
pub trait Attachment: ItemStream<RenderItem> {
    /// Reports a grid size for this viewer, which makes cmux size the terminal to it.
    fn resize(&mut self, size: Size) -> BackendResult<()>;
    fn release(&mut self) -> BackendResult<()>;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    Left,
    Right,
    Up,
    Down,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MouseKind {
    Down,
    Up,
    Move,
    Wheel,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MouseButton {
    Left,
    Middle,
    Right,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Modifier {
    Shift,
    Control,
    Alt,
    Meta,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MouseInput {
    pub kind: MouseKind,
    pub row: u16,
    pub col: u16,
    pub button: Option<MouseButton>,
    pub delta_rows: Option<i32>,
    pub modifiers: Vec<Modifier>,
}

pub trait Cmux: Send + Sync + 'static {
    /// The cmux session this backend serves, for `Welcome.session_name`.
    fn session_name(&self) -> String;
    fn events(&self) -> BackendResult<Box<dyn ItemStream<SessionItem>>>;
    /// Attaches without reporting a size, so the Mac's grid stays as it is.
    fn attach(&self, terminal: &str) -> BackendResult<Box<dyn Attachment>>;
    /// Every input carries an idempotency key; repeating a key never repeats the input.
    fn write(&self, terminal: &str, text: &str, key: &str) -> BackendResult<()>;
    fn keys(&self, terminal: &str, keys: &[String], key: &str) -> BackendResult<()>;
    fn mouse(&self, terminal: &str, mouse: &MouseInput, key: &str) -> BackendResult<()>;
    fn focus_direction(
        &self,
        pane: &PanePath,
        direction: Direction,
        key: &str,
    ) -> BackendResult<()>;
    fn focus_pane(&self, pane: &PanePath, key: &str) -> BackendResult<()>;
    fn focus_tab(&self, pane: &PanePath, tab: &str, key: &str) -> BackendResult<()>;
}

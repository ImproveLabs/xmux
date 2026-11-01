//! [`Cmux`] that plays a recorded [`Trace`]: the session events and each terminal's attach
//! stream, either as fast as they are read (tests) or at the recorded pace (`serve --replay`).
//! Input is recorded instead of sent anywhere.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::backend::{
    Attachment, BackendError, BackendResult, Cmux, Direction, ItemStream, MouseInput, Poll,
};
use crate::model::{RenderItem, SessionItem, Size};
use crate::topology::PanePath;
use crate::trace::Trace;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Pace {
    /// Every item as soon as it is polled.
    Instant,
    /// Items at their recorded offsets from the moment the stream opens.
    Recorded,
}

/// An input the replayed "terminal" received.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecordedInput {
    Write {
        terminal: String,
        text: String,
        key: String,
    },
    Keys {
        terminal: String,
        keys: Vec<String>,
        key: String,
    },
    Mouse {
        terminal: String,
        mouse: MouseInput,
        key: String,
    },
    Focus {
        pane: PanePath,
        direction: Direction,
    },
    FocusPane {
        pane: PanePath,
    },
    FocusTab {
        pane: PanePath,
        tab: String,
    },
    Resize {
        terminal: String,
        size: Size,
    },
}

pub struct ReplayCmux {
    trace: Arc<Trace>,
    pace: Pace,
    looping: bool,
    inputs: Arc<Mutex<Vec<RecordedInput>>>,
}

impl ReplayCmux {
    pub fn new(trace: Trace, pace: Pace) -> ReplayCmux {
        ReplayCmux {
            trace: Arc::new(trace),
            pace,
            looping: false,
            inputs: Arc::default(),
        }
    }

    /// Restarts each attach stream from its first item after a short pause once it runs out.
    pub fn looping(mut self, looping: bool) -> ReplayCmux {
        self.looping = looping;
        self
    }

    pub fn inputs(&self) -> Vec<RecordedInput> {
        self.inputs.lock().unwrap().clone()
    }

    fn record(&self, input: RecordedInput) {
        self.inputs.lock().unwrap().push(input);
    }
}

struct Player<T> {
    items: Vec<(u64, T)>,
    next: usize,
    pace: Pace,
    opened: Instant,
    looping: bool,
    canceled: bool,
}

impl<T: Clone + Send> Player<T> {
    fn new(items: Vec<(u64, T)>, pace: Pace, looping: bool) -> Player<T> {
        let base = items.first().map(|(t, _)| *t).unwrap_or(0);
        let items = items
            .into_iter()
            .map(|(t, item)| (t - base, item))
            .collect();
        Player {
            items,
            next: 0,
            pace,
            opened: Instant::now(),
            looping,
            canceled: false,
        }
    }

    fn poll(&mut self, timeout: Duration) -> Poll<T> {
        if self.canceled {
            return Poll::End {
                reason: "canceled".into(),
            };
        }
        if self.next >= self.items.len() {
            if self.looping && !self.items.is_empty() {
                std::thread::sleep(timeout.min(Duration::from_millis(50)));
                if self.opened.elapsed() >= self.duration() + Duration::from_secs(2) {
                    self.next = 0;
                    self.opened = Instant::now();
                }
                return Poll::TimedOut;
            }
            // A quiet stream stays open, like a terminal nobody types into.
            std::thread::sleep(timeout);
            return Poll::TimedOut;
        }
        let (t, item) = &self.items[self.next];
        if self.pace == Pace::Recorded {
            let due = self.opened + Duration::from_millis(*t);
            let now = Instant::now();
            if due > now {
                let wait = due - now;
                if wait > timeout {
                    std::thread::sleep(timeout);
                    return Poll::TimedOut;
                }
                std::thread::sleep(wait);
            }
        }
        self.next += 1;
        Poll::Item(item.clone())
    }

    fn duration(&self) -> Duration {
        Duration::from_millis(self.items.last().map(|(t, _)| *t).unwrap_or(0))
    }
}

struct EventsPlayer(Player<SessionItem>);

impl ItemStream<SessionItem> for EventsPlayer {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<SessionItem>> {
        Ok(self.0.poll(timeout))
    }

    fn cancel(&mut self) {
        self.0.canceled = true;
    }
}

struct AttachPlayer {
    terminal: String,
    player: Player<RenderItem>,
    inputs: Arc<Mutex<Vec<RecordedInput>>>,
}

impl ItemStream<RenderItem> for AttachPlayer {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<RenderItem>> {
        Ok(self.player.poll(timeout))
    }

    fn cancel(&mut self) {
        self.player.canceled = true;
    }
}

impl Attachment for AttachPlayer {
    fn resize(&mut self, size: Size) -> BackendResult<()> {
        self.inputs.lock().unwrap().push(RecordedInput::Resize {
            terminal: self.terminal.clone(),
            size,
        });
        Ok(())
    }

    fn release(&mut self) -> BackendResult<()> {
        Ok(())
    }
}

impl Cmux for ReplayCmux {
    fn session_name(&self) -> String {
        format!("replay:{}", self.trace.header.scenario)
    }

    fn events(&self) -> BackendResult<Box<dyn ItemStream<SessionItem>>> {
        // Session events never loop: replaying a topology twice would only repeat its deltas.
        Ok(Box::new(EventsPlayer(Player::new(
            self.trace.events.clone(),
            self.pace,
            false,
        ))))
    }

    fn attach(&self, terminal: &str) -> BackendResult<Box<dyn Attachment>> {
        let attach = self
            .trace
            .attach(terminal)
            .ok_or_else(|| BackendError::UnknownTerminal(terminal.to_string()))?;
        Ok(Box::new(AttachPlayer {
            terminal: terminal.to_string(),
            player: Player::new(attach.items.clone(), self.pace, self.looping),
            inputs: self.inputs.clone(),
        }))
    }

    fn write(&self, terminal: &str, text: &str, key: &str) -> BackendResult<()> {
        self.record(RecordedInput::Write {
            terminal: terminal.into(),
            text: text.into(),
            key: key.into(),
        });
        Ok(())
    }

    fn keys(&self, terminal: &str, keys: &[String], key: &str) -> BackendResult<()> {
        self.record(RecordedInput::Keys {
            terminal: terminal.into(),
            keys: keys.to_vec(),
            key: key.into(),
        });
        Ok(())
    }

    fn mouse(&self, terminal: &str, mouse: &MouseInput, key: &str) -> BackendResult<()> {
        self.record(RecordedInput::Mouse {
            terminal: terminal.into(),
            mouse: mouse.clone(),
            key: key.into(),
        });
        Ok(())
    }

    fn focus_direction(
        &self,
        pane: &PanePath,
        direction: Direction,
        _key: &str,
    ) -> BackendResult<()> {
        self.record(RecordedInput::Focus {
            pane: pane.clone(),
            direction,
        });
        Ok(())
    }

    fn focus_pane(&self, pane: &PanePath, _key: &str) -> BackendResult<()> {
        self.record(RecordedInput::FocusPane { pane: pane.clone() });
        Ok(())
    }

    fn focus_tab(&self, pane: &PanePath, tab: &str, _key: &str) -> BackendResult<()> {
        self.record(RecordedInput::FocusTab {
            pane: pane.clone(),
            tab: tab.to_string(),
        });
        Ok(())
    }
}

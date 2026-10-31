//! The hub: one per bridge process. It owns the session mirror, one attach stream per terminal
//! that any headset shows (shared between headsets), and the list of connected headsets.
//!
//! Everything runs on one task, so the state needs no locks. cmux streams run on their own
//! threads (the SDK and the raw client are blocking) and send their items here; mutations run in
//! order on one input thread. Rows go out in batches every `flush_interval` (16 ms by default), so a
//! burst of patches to one row costs one row on the wire.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::mpsc as std_mpsc;
use std::time::Duration;

use tokio::sync::mpsc;
use xmux_proto::frame::Payload;
use xmux_proto::{AttachState, AttachStatus, Frame, Input, Pong, Stats, encode_frame};

use crate::backend::{Cmux, Direction, Modifier, MouseButton, MouseInput, MouseKind, Poll};
use crate::conformance::frame_route;
use crate::model::{RenderItem, SessionItem, Size};
use crate::terminal::TerminalState;
use crate::topology::TopologyState;

#[derive(Clone, Debug)]
pub struct HubConfig {
    pub flush_interval: Duration,
    pub checksum_interval: Duration,
    pub stats_interval: Duration,
    /// Wait before reopening an attach stream that ended while headsets still show it.
    pub reattach_delay: Duration,
    /// Frames a headset may have queued before the hub gives up on it.
    pub client_queue: usize,
}

impl Default for HubConfig {
    fn default() -> Self {
        HubConfig {
            flush_interval: Duration::from_millis(16),
            checksum_interval: Duration::from_secs(2),
            stats_interval: Duration::from_secs(1),
            reattach_delay: Duration::from_millis(500),
            client_queue: 4096,
        }
    }
}

/// An encoded wire frame, shared between the headsets it goes to.
pub type Wire = Arc<[u8]>;

enum Msg {
    Session(SessionItem),
    Render {
        terminal: u32,
        epoch: u64,
        item: RenderItem,
    },
    AttachEnded {
        terminal: u32,
        epoch: u64,
        reason: String,
    },
    Reattach {
        terminal: u32,
        epoch: u64,
    },
    Join {
        client: u64,
        device: String,
        tx: mpsc::Sender<Wire>,
    },
    Leave {
        client: u64,
    },
    FromClient {
        client: u64,
        frame: Frame,
    },
    Status {
        reply: tokio::sync::oneshot::Sender<HubStatus>,
    },
}

/// What `xmux-bridge status` prints.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HubStatus {
    pub session: String,
    pub clients: Vec<String>,
    pub attached_terminals: usize,
    pub screens: usize,
}

enum AttachCtl {
    Resize(Size),
    Stop,
}

/// Handle to the hub task. Cheap to clone.
#[derive(Clone)]
pub struct Hub {
    tx: mpsc::UnboundedSender<Msg>,
    session_name: String,
    next_client: Arc<std::sync::atomic::AtomicU64>,
}

/// One headset's connection to the hub: frames from the headset go in through [`send`], wire
/// frames for it come out of `outgoing`. Dropping it (or its [`ClientSender`] half) detaches the
/// headset.
///
/// [`send`]: ClientLink::send
pub struct ClientLink {
    pub id: u64,
    pub outgoing: mpsc::Receiver<Wire>,
    sender: ClientSender,
}

/// The sending half of a [`ClientLink`], for a reader task that runs apart from the writer.
pub struct ClientSender {
    id: u64,
    tx: mpsc::UnboundedSender<Msg>,
}

impl ClientSender {
    pub fn send(&self, frame: Frame) {
        let _ = self.tx.send(Msg::FromClient {
            client: self.id,
            frame,
        });
    }
}

impl Drop for ClientSender {
    fn drop(&mut self) {
        let _ = self.tx.send(Msg::Leave { client: self.id });
    }
}

impl ClientLink {
    pub fn send(&self, frame: Frame) {
        self.sender.send(frame);
    }

    pub fn split(self) -> (ClientSender, mpsc::Receiver<Wire>) {
        (self.sender, self.outgoing)
    }
}

impl Hub {
    /// Starts the hub on the current tokio runtime.
    pub fn start(cmux: Arc<dyn Cmux>, config: HubConfig) -> Hub {
        let (tx, rx) = mpsc::unbounded_channel();
        let session_name = cmux.session_name();
        spawn_events(cmux.clone(), tx.clone());
        let state = State {
            cmux,
            input: spawn_input_worker(),
            config,
            tx: tx.clone(),
            topology: TopologyState::new(),
            has_topology: false,
            terminals: HashMap::new(),
            clients: HashMap::new(),
            next_epoch: 1,
            input_seq: 0,
        };
        tokio::spawn(state.run(rx));
        Hub {
            tx,
            session_name,
            next_client: Arc::new(1.into()),
        }
    }

    pub fn session_name(&self) -> &str {
        &self.session_name
    }

    /// Registers a headset that finished its handshake. `device` keys idempotent input.
    pub fn join(&self, device: &str, queue: usize) -> ClientLink {
        let id = self
            .next_client
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let (out_tx, outgoing) = mpsc::channel(queue);
        let _ = self.tx.send(Msg::Join {
            client: id,
            device: device.to_string(),
            tx: out_tx,
        });
        ClientLink {
            id,
            outgoing,
            sender: ClientSender {
                id,
                tx: self.tx.clone(),
            },
        }
    }

    pub async fn status(&self) -> HubStatus {
        let (reply, rx) = tokio::sync::oneshot::channel();
        if self.tx.send(Msg::Status { reply }).is_err() {
            return HubStatus::default();
        }
        rx.await.unwrap_or_default()
    }
}

/// Session events on their own thread; reconnects after a second if the stream ends.
fn spawn_events(cmux: Arc<dyn Cmux>, tx: mpsc::UnboundedSender<Msg>) {
    std::thread::Builder::new()
        .name("xmux-events".into())
        .spawn(move || {
            loop {
                match cmux.events() {
                    Ok(mut stream) => loop {
                        if tx.is_closed() {
                            stream.cancel();
                            return;
                        }
                        match stream.poll(Duration::from_millis(250)) {
                            Ok(Poll::Item(item)) => {
                                if tx.send(Msg::Session(item)).is_err() {
                                    return;
                                }
                            }
                            Ok(Poll::TimedOut) => {}
                            Ok(Poll::End { reason }) => {
                                tracing::warn!(%reason, "session events ended; reconnecting");
                                break;
                            }
                            Err(error) => {
                                tracing::warn!(%error, "session events failed; reconnecting");
                                break;
                            }
                        }
                    },
                    Err(error) => tracing::warn!(%error, "cannot open session events"),
                }
                if tx.is_closed() {
                    return;
                }
                std::thread::sleep(Duration::from_secs(1));
            }
        })
        .expect("spawn events thread");
}

fn spawn_attach(
    cmux: Arc<dyn Cmux>,
    cmux_id: String,
    terminal: u32,
    epoch: u64,
    tx: mpsc::UnboundedSender<Msg>,
    ctl: std_mpsc::Receiver<AttachCtl>,
) {
    std::thread::Builder::new()
        .name(format!("xmux-attach-{terminal}"))
        .spawn(move || {
            let mut stream = match cmux.attach(&cmux_id) {
                Ok(s) => s,
                Err(error) => {
                    let _ = tx.send(Msg::AttachEnded {
                        terminal,
                        epoch,
                        reason: error.to_string(),
                    });
                    return;
                }
            };
            loop {
                loop {
                    match ctl.try_recv() {
                        Ok(AttachCtl::Resize(size)) => {
                            if let Err(error) = stream.resize(size) {
                                tracing::warn!(%error, terminal, "viewer resize failed");
                            }
                        }
                        Ok(AttachCtl::Stop) | Err(std_mpsc::TryRecvError::Disconnected) => {
                            let _ = stream.release();
                            stream.cancel();
                            return;
                        }
                        Err(std_mpsc::TryRecvError::Empty) => break,
                    }
                }
                match stream.poll(Duration::from_millis(50)) {
                    Ok(Poll::Item(item)) => {
                        if tx
                            .send(Msg::Render {
                                terminal,
                                epoch,
                                item,
                            })
                            .is_err()
                        {
                            stream.cancel();
                            return;
                        }
                    }
                    Ok(Poll::TimedOut) => {}
                    Ok(Poll::End { reason }) => {
                        let _ = tx.send(Msg::AttachEnded {
                            terminal,
                            epoch,
                            reason,
                        });
                        return;
                    }
                    Err(error) => {
                        let _ = tx.send(Msg::AttachEnded {
                            terminal,
                            epoch,
                            reason: error.to_string(),
                        });
                        return;
                    }
                }
            }
        })
        .expect("spawn attach thread");
}

struct TerminalEntry {
    cmux_id: String,
    state: TerminalState,
    subscribers: HashSet<u64>,
    /// Control channel of the running attach thread, if any.
    ctl: Option<std_mpsc::Sender<AttachCtl>>,
    /// Which attach thread is current; items from older ones are dropped.
    epoch: u64,
    status: AttachStatus,
    checksummed: (u32, u32),
}

struct ClientEntry {
    device: String,
    tx: mpsc::Sender<Wire>,
    attached: HashSet<u32>,
    rows_sent: u32,
    bytes_sent: u32,
}

/// A cmux mutation (input, focus) for the input worker.
type Job = Box<dyn FnOnce() + Send>;

/// Runs mutations one at a time on their own thread, so keystrokes reach cmux in the order the
/// headset sent them.
fn spawn_input_worker() -> std_mpsc::Sender<Job> {
    let (tx, rx) = std_mpsc::channel::<Job>();
    std::thread::Builder::new()
        .name("xmux-input".into())
        .spawn(move || {
            for job in rx {
                job();
            }
        })
        .expect("spawn input thread");
    tx
}

struct State {
    cmux: Arc<dyn Cmux>,
    input: std_mpsc::Sender<Job>,
    config: HubConfig,
    tx: mpsc::UnboundedSender<Msg>,
    topology: TopologyState,
    has_topology: bool,
    terminals: HashMap<u32, TerminalEntry>,
    clients: HashMap<u64, ClientEntry>,
    next_epoch: u64,
    input_seq: u64,
}

fn encode(frame: &Frame) -> Wire {
    let (id, seq) = frame_route(frame);
    encode_frame(frame, id, seq).into()
}

fn attach_state(terminal: u32, status: AttachStatus, message: &str) -> Frame {
    Frame {
        payload: Some(Payload::AttachState(AttachState {
            terminal,
            status: status as i32,
            message: message.to_string(),
        })),
    }
}

impl State {
    async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Msg>) {
        let mut flush = tokio::time::interval(self.config.flush_interval);
        let mut checksum = tokio::time::interval(self.config.checksum_interval);
        let mut stats = tokio::time::interval(self.config.stats_interval);
        for timer in [&mut flush, &mut checksum, &mut stats] {
            timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        }
        loop {
            tokio::select! {
                msg = rx.recv() => match msg {
                    Some(msg) => self.handle(msg),
                    None => break,
                },
                _ = flush.tick() => self.flush_all(),
                _ = checksum.tick() => self.checksums(),
                _ = stats.tick() => self.stats(),
            }
        }
        for t in self.terminals.values() {
            if let Some(ctl) = &t.ctl {
                let _ = ctl.send(AttachCtl::Stop);
            }
        }
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::Session(item) => self.on_session(&item),
            Msg::Render {
                terminal,
                epoch,
                item,
            } => {
                let Some(t) = self.terminals.get_mut(&terminal) else {
                    return;
                };
                if t.epoch != epoch {
                    return;
                }
                t.state.apply(&item);
                if t.status != AttachStatus::Attached && t.state.is_ready() {
                    t.status = AttachStatus::Attached;
                    // The first frames of the stream go out before clients learn it is attached.
                    self.flush_terminal(terminal);
                    self.send_to_subscribers(
                        terminal,
                        &attach_state(terminal, AttachStatus::Attached, ""),
                    );
                }
            }
            Msg::AttachEnded {
                terminal,
                epoch,
                reason,
            } => self.on_attach_ended(terminal, epoch, reason),
            Msg::Reattach { terminal, epoch } => {
                let current = self
                    .terminals
                    .get(&terminal)
                    .is_some_and(|t| t.epoch == epoch && t.ctl.is_none());
                if current && !self.terminals[&terminal].subscribers.is_empty() {
                    self.start_attach(terminal);
                }
            }
            Msg::Join { client, device, tx } => {
                self.clients.insert(
                    client,
                    ClientEntry {
                        device,
                        tx,
                        attached: HashSet::new(),
                        rows_sent: 0,
                        bytes_sent: 0,
                    },
                );
                if self.has_topology {
                    let frame = self.topology.frame();
                    self.send_to_client(client, encode(&frame));
                }
            }
            Msg::Leave { client } => {
                if let Some(c) = self.clients.remove(&client) {
                    for terminal in c.attached {
                        self.unsubscribe(terminal, client);
                    }
                }
            }
            Msg::FromClient { client, frame } => self.on_client_frame(client, frame),
            Msg::Status { reply } => {
                let _ = reply.send(HubStatus {
                    session: self.cmux.session_name(),
                    clients: self.clients.values().map(|c| c.device.clone()).collect(),
                    attached_terminals: self.terminals.values().filter(|t| t.ctl.is_some()).count(),
                    screens: self.topology.doc().screens.len(),
                });
            }
        }
    }

    fn on_session(&mut self, item: &SessionItem) {
        if !self.topology.apply(item) && self.has_topology {
            return;
        }
        self.has_topology = true;
        let frame = encode(&self.topology.frame());
        let ids: Vec<u64> = self.clients.keys().copied().collect();
        for client in ids {
            self.send_to_client(client, frame.clone());
        }
        // Terminals that disappeared: tell their viewers and stop streaming.
        let alive: HashSet<String> = self.topology.terminal_ids().into_iter().collect();
        let gone: Vec<u32> = self
            .terminals
            .iter()
            .filter(|(_, t)| !alive.contains(&t.cmux_id))
            .map(|(id, _)| *id)
            .collect();
        for terminal in gone {
            self.send_to_subscribers(
                terminal,
                &attach_state(terminal, AttachStatus::Detached, "terminal closed"),
            );
            if let Some(t) = self.terminals.remove(&terminal) {
                if let Some(ctl) = t.ctl {
                    let _ = ctl.send(AttachCtl::Stop);
                }
                for client in t.subscribers {
                    if let Some(c) = self.clients.get_mut(&client) {
                        c.attached.remove(&terminal);
                    }
                }
            }
        }
    }

    fn on_attach_ended(&mut self, terminal: u32, epoch: u64, reason: String) {
        let Some(t) = self.terminals.get_mut(&terminal) else {
            return;
        };
        if t.epoch != epoch {
            return;
        }
        t.ctl = None;
        if t.subscribers.is_empty() {
            return;
        }
        let exists = self.topology.terminal_ids().contains(&t.cmux_id);
        if !exists && self.has_topology {
            t.status = AttachStatus::Detached;
            self.send_to_subscribers(
                terminal,
                &attach_state(terminal, AttachStatus::Detached, &reason),
            );
            return;
        }
        // Snapshot on gap: reopen the stream; its first item is a fresh snapshot, which starts a
        // new generation for every viewer.
        tracing::info!(terminal, %reason, "attach stream ended; reattaching");
        t.status = AttachStatus::Stalled;
        self.send_to_subscribers(
            terminal,
            &attach_state(terminal, AttachStatus::Stalled, &reason),
        );
        let tx = self.tx.clone();
        let delay = self.config.reattach_delay;
        tokio::spawn(async move {
            tokio::time::sleep(delay).await;
            let _ = tx.send(Msg::Reattach { terminal, epoch });
        });
    }

    fn start_attach(&mut self, terminal: u32) {
        let epoch = self.next_epoch;
        self.next_epoch += 1;
        let Some(t) = self.terminals.get_mut(&terminal) else {
            return;
        };
        let (ctl, ctl_rx) = std_mpsc::channel();
        t.ctl = Some(ctl);
        t.epoch = epoch;
        if t.status != AttachStatus::Stalled {
            t.status = AttachStatus::Attaching;
        }
        spawn_attach(
            self.cmux.clone(),
            t.cmux_id.clone(),
            terminal,
            epoch,
            self.tx.clone(),
            ctl_rx,
        );
    }

    fn on_client_frame(&mut self, client: u64, frame: Frame) {
        match frame.payload {
            Some(Payload::AttachTerminal(a)) => self.subscribe(a.terminal, client),
            Some(Payload::DetachTerminal(d)) => {
                if let Some(c) = self.clients.get_mut(&client) {
                    c.attached.remove(&d.terminal);
                }
                self.unsubscribe(d.terminal, client);
            }
            Some(Payload::ResyncTerminal(r)) => {
                self.flush_terminal(r.terminal);
                if let Some(t) = self.terminals.get(&r.terminal)
                    && t.subscribers.contains(&client)
                {
                    for f in t.state.snapshot_frames() {
                        self.send_to_client(client, encode(&f));
                    }
                }
            }
            Some(Payload::Input(input)) => self.input(client, input),
            Some(Payload::Resize(r)) => {
                if let Some(ctl) = self.terminals.get(&r.terminal).and_then(|t| t.ctl.as_ref()) {
                    let size = Size {
                        cols: r.cols.clamp(1, 1000) as u16,
                        rows: r.rows.clamp(1, 1000) as u16,
                    };
                    let _ = ctl.send(AttachCtl::Resize(size));
                }
            }
            Some(Payload::FocusDirection(f)) => {
                let Some(screen) = self.topology.ids().cmux(f.screen).map(str::to_string) else {
                    return;
                };
                let Some(pane) = self.topology.focused_pane(&screen) else {
                    return;
                };
                let direction = match xmux_proto::Direction::try_from(f.direction) {
                    Ok(xmux_proto::Direction::Left) => Direction::Left,
                    Ok(xmux_proto::Direction::Right) => Direction::Right,
                    Ok(xmux_proto::Direction::Up) => Direction::Up,
                    Ok(xmux_proto::Direction::Down) | Err(_) => Direction::Down,
                };
                let key = self.mutation_key(client, None);
                let cmux = self.cmux.clone();
                let _ = self.input.send(Box::new(move || {
                    if let Err(error) = cmux.focus_direction(&pane, direction, &key) {
                        tracing::warn!(%error, "focus direction failed");
                    }
                }));
            }
            Some(Payload::FocusPane(f)) => {
                let Some(pane) = self.topology.ids().cmux(f.pane).map(str::to_string) else {
                    return;
                };
                let Some(path) = self.topology.pane_path(&pane) else {
                    return;
                };
                let key = self.mutation_key(client, None);
                let cmux = self.cmux.clone();
                let _ = self.input.send(Box::new(move || {
                    if let Err(error) = cmux.focus_pane(&path, &key) {
                        tracing::warn!(%error, "focus pane failed");
                    }
                }));
            }
            Some(Payload::FocusTab(f)) => {
                let Some(tab) = self.topology.ids().cmux(f.tab).map(str::to_string) else {
                    return;
                };
                let Some(pane) = self
                    .topology
                    .doc()
                    .tabs
                    .iter()
                    .find(|t| t.id == tab)
                    .map(|t| t.pane_id.clone())
                else {
                    return;
                };
                let Some(path) = self.topology.pane_path(&pane) else {
                    return;
                };
                let key = self.mutation_key(client, None);
                let cmux = self.cmux.clone();
                let _ = self.input.send(Box::new(move || {
                    if let Err(error) = cmux.focus_tab(&path, &tab, &key) {
                        tracing::warn!(%error, "focus tab failed");
                    }
                }));
            }
            Some(Payload::Ping(p)) => {
                let pong = Frame {
                    payload: Some(Payload::Pong(Pong {
                        nonce: p.nonce,
                        sent_ns: p.sent_ns,
                    })),
                };
                self.send_to_client(client, encode(&pong));
            }
            _ => {}
        }
    }

    /// cmux idempotency key for one input: stable for the same headset and the same client
    /// counter, so a resend after a reconnect is applied once.
    fn mutation_key(&mut self, client: u64, idempotency: Option<u64>) -> String {
        let device = self
            .clients
            .get(&client)
            .map(|c| c.device.as_str())
            .unwrap_or("anon");
        let device: String = device
            .chars()
            .filter(|c| c.is_ascii_alphanumeric())
            .take(24)
            .collect();
        match idempotency {
            Some(n) if n != 0 => format!("xmux-{device}-{n}"),
            _ => {
                self.input_seq += 1;
                format!("xmux-{device}-hub{}-{}", std::process::id(), self.input_seq)
            }
        }
    }

    fn input(&mut self, client: u64, input: Input) {
        let Some(terminal) = self.topology.ids().cmux(input.terminal).map(str::to_string) else {
            return;
        };
        let key = self.mutation_key(client, Some(input.idempotency));
        let cmux = self.cmux.clone();
        let call: Box<dyn FnOnce() -> crate::backend::BackendResult<()> + Send> =
            if !input.text.is_empty() {
                let text = input.text;
                Box::new(move || cmux.write(&terminal, &text, &key))
            } else if !input.keys.is_empty() {
                let keys: Vec<String> = input
                    .keys
                    .iter()
                    .map(|k| key_chord(&k.key, k.mods))
                    .collect();
                Box::new(move || cmux.keys(&terminal, &keys, &key))
            } else if let Some(m) = input.mouse {
                let mouse = mouse_input(&m);
                Box::new(move || cmux.mouse(&terminal, &mouse, &key))
            } else {
                return;
            };
        let _ = self.input.send(Box::new(move || {
            if let Err(error) = call() {
                tracing::warn!(%error, "input failed");
            }
        }));
    }

    fn subscribe(&mut self, terminal: u32, client: u64) {
        let Some(cmux_id) = self.topology.ids().cmux(terminal).map(str::to_string) else {
            self.send_to_client(
                client,
                encode(&attach_state(
                    terminal,
                    AttachStatus::Error,
                    "unknown terminal",
                )),
            );
            return;
        };
        if let Some(c) = self.clients.get_mut(&client) {
            c.attached.insert(terminal);
        }
        let entry = self
            .terminals
            .entry(terminal)
            .or_insert_with(|| TerminalEntry {
                cmux_id,
                state: TerminalState::new(terminal),
                subscribers: HashSet::new(),
                ctl: None,
                epoch: 0,
                status: AttachStatus::Attaching,
                checksummed: (0, 0),
            });
        entry.subscribers.insert(client);
        if entry.ctl.is_none() && entry.status != AttachStatus::Stalled {
            self.start_attach(terminal);
        }
        let status = self.terminals[&terminal].status;
        if status == AttachStatus::Attached {
            self.flush_terminal(terminal);
            for f in self.terminals[&terminal].state.snapshot_frames() {
                self.send_to_client(client, encode(&f));
            }
        }
        self.send_to_client(client, encode(&attach_state(terminal, status, "")));
    }

    fn unsubscribe(&mut self, terminal: u32, client: u64) {
        let Some(t) = self.terminals.get_mut(&terminal) else {
            return;
        };
        t.subscribers.remove(&client);
        if t.subscribers.is_empty() {
            if let Some(ctl) = t.ctl.take() {
                let _ = ctl.send(AttachCtl::Stop);
            }
            self.terminals.remove(&terminal);
        }
    }

    fn flush_all(&mut self) {
        let dirty: Vec<u32> = self
            .terminals
            .iter()
            .filter(|(_, t)| t.state.has_changes())
            .map(|(id, _)| *id)
            .collect();
        for terminal in dirty {
            self.flush_terminal(terminal);
        }
    }

    fn flush_terminal(&mut self, terminal: u32) {
        let Some(t) = self.terminals.get_mut(&terminal) else {
            return;
        };
        if !t.state.has_changes() {
            return;
        }
        let frames = t.state.take_frames();
        for frame in frames {
            let rows = match &frame.payload {
                Some(Payload::Rows(r)) => r.dirty.len() as u32,
                _ => 0,
            };
            let wire = encode(&frame);
            let subscribers: Vec<u64> = self.terminals[&terminal]
                .subscribers
                .iter()
                .copied()
                .collect();
            for client in subscribers {
                if let Some(c) = self.clients.get_mut(&client) {
                    c.rows_sent += rows;
                }
                self.send_to_client(client, wire.clone());
            }
        }
    }

    fn checksums(&mut self) {
        let due: Vec<u32> = self
            .terminals
            .iter()
            .filter(|(_, t)| {
                t.state.is_ready() && (t.state.generation(), t.state.sequence()) != t.checksummed
            })
            .map(|(id, _)| *id)
            .collect();
        for terminal in due {
            self.flush_terminal(terminal);
            let t = self.terminals.get_mut(&terminal).unwrap();
            t.checksummed = (t.state.generation(), t.state.sequence());
            let frame = t.state.checksum_frame();
            self.send_to_subscribers(terminal, &frame);
        }
    }

    fn stats(&mut self) {
        let secs = self.config.stats_interval.as_secs_f32().max(0.001);
        let ids: Vec<u64> = self.clients.keys().copied().collect();
        for client in ids {
            let c = self.clients.get_mut(&client).unwrap();
            let frame = Frame {
                payload: Some(Payload::Stats(Stats {
                    rows_per_second: (c.rows_sent as f32 / secs) as u32,
                    bytes_per_second: (c.bytes_sent as f32 / secs) as u32,
                    rtt_ms: 0,
                })),
            };
            c.rows_sent = 0;
            c.bytes_sent = 0;
            self.send_to_client(client, encode(&frame));
        }
    }

    fn send_to_subscribers(&mut self, terminal: u32, frame: &Frame) {
        let Some(t) = self.terminals.get(&terminal) else {
            return;
        };
        let wire = encode(frame);
        let subscribers: Vec<u64> = t.subscribers.iter().copied().collect();
        for client in subscribers {
            self.send_to_client(client, wire.clone());
        }
    }

    /// Queues a frame for one headset. A headset that cannot keep up is dropped; it reconnects
    /// and resyncs from snapshots.
    fn send_to_client(&mut self, client: u64, wire: Wire) {
        let Some(c) = self.clients.get_mut(&client) else {
            return;
        };
        c.bytes_sent = c.bytes_sent.saturating_add(wire.len() as u32);
        if c.tx.try_send(wire).is_err() {
            tracing::warn!(client, "headset is not keeping up; dropping it");
            if let Some(c) = self.clients.remove(&client) {
                for terminal in c.attached {
                    self.unsubscribe(terminal, client);
                }
            }
        }
    }
}

/// `KeyPress{key, mods}` as a cmux key chord: `ctrl+alt+shift+<key>` (meta maps to alt, the
/// terminal convention).
pub fn key_chord(key: &str, mods: u32) -> String {
    let mut chord = String::new();
    if mods & 2 != 0 {
        chord.push_str("ctrl+");
    }
    if mods & (4 | 8) != 0 {
        chord.push_str("alt+");
    }
    if mods & 1 != 0 {
        chord.push_str("shift+");
    }
    chord.push_str(&key.to_lowercase());
    chord
}

fn mouse_input(m: &xmux_proto::MouseEvent) -> MouseInput {
    use xmux_proto::MouseAction;
    let kind = match MouseAction::try_from(m.action) {
        Ok(MouseAction::Press) => MouseKind::Down,
        Ok(MouseAction::Release) => MouseKind::Up,
        Ok(MouseAction::Move) => MouseKind::Move,
        Ok(MouseAction::Scroll) | Err(_) => MouseKind::Wheel,
    };
    let button = match (kind, m.button) {
        (MouseKind::Wheel | MouseKind::Move, _) => None,
        (_, 1) => Some(MouseButton::Middle),
        (_, 2) => Some(MouseButton::Right),
        _ => Some(MouseButton::Left),
    };
    let mut modifiers = Vec::new();
    for (bit, modifier) in [
        (1, Modifier::Shift),
        (2, Modifier::Control),
        (4, Modifier::Alt),
        (8, Modifier::Meta),
    ] {
        if m.mods & bit != 0 {
            modifiers.push(modifier);
        }
    }
    MouseInput {
        kind,
        row: m.row.min(u32::from(u16::MAX)) as u16,
        col: m.col.min(u32::from(u16::MAX)) as u16,
        button,
        delta_rows: (kind == MouseKind::Wheel).then_some(m.dy),
        modifiers,
    }
}

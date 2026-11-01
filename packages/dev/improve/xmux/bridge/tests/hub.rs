//! The hub against a replayed trace and a scripted fake cmux: topology, attach and snapshot,
//! shared streams, input mapping, resync, gap recovery, opt-in resize, detach.

use std::collections::VecDeque;
use std::sync::mpsc as std_mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use xmux_bridge::backend::{
    Attachment, BackendResult, Cmux, Direction, ItemStream, MouseInput, MouseKind, Poll,
};
use xmux_bridge::conformance::{ExpectedFile, fixtures_dir};
use xmux_bridge::grid::{Applied, Grid};
use xmux_bridge::hub::{ClientLink, Hub, HubConfig, key_chord};
use xmux_bridge::model::{
    Change, Layout, PaneDoc, RenderCursor, RenderItem, RenderRow, RenderRun, ResourceKind,
    ScreenDoc, SessionDoc, SessionItem, Size, TabDoc, TerminalDoc, WorkspaceDoc,
};
use xmux_bridge::replay::{Pace, RecordedInput, ReplayCmux};
use xmux_bridge::topology::PanePath;
use xmux_bridge::trace::Trace;
use xmux_proto::frame::Payload;
use xmux_proto::{
    AttachStatus, AttachTerminal, DetachTerminal, Frame, Input, KeyPress, MouseAction, MouseEvent,
    Ping, Resize, ResyncTerminal, Topology, decode_frame,
};

fn config() -> HubConfig {
    HubConfig {
        flush_interval: Duration::from_millis(5),
        checksum_interval: Duration::from_millis(200),
        reattach_delay: Duration::from_millis(20),
        ..HubConfig::default()
    }
}

async fn next(link: &mut ClientLink) -> Frame {
    let wire = tokio::time::timeout(Duration::from_secs(5), link.outgoing.recv())
        .await
        .expect("a frame within 5 s")
        .expect("the hub is alive");
    decode_frame(&wire).unwrap().1
}

async fn topology(link: &mut ClientLink) -> Topology {
    loop {
        if let Some(Payload::Topology(t)) = next(link).await.payload {
            return t;
        }
    }
}

fn send(link: &ClientLink, payload: Payload) {
    link.send(Frame {
        payload: Some(payload),
    });
}

/// Applies frames until `done(grid)` holds; returns the attach states seen on the way.
async fn follow(
    link: &mut ClientLink,
    terminal: u32,
    grid: &mut Grid,
    done: impl Fn(&Grid) -> bool,
) -> Vec<AttachStatus> {
    let mut states = Vec::new();
    while !(grid.is_synced() && done(grid)) {
        let frame = next(link).await;
        match &frame.payload {
            Some(Payload::AttachState(a)) if a.terminal == terminal => {
                states.push(AttachStatus::try_from(a.status).unwrap())
            }
            Some(Payload::Rows(r)) if r.terminal == terminal => {
                assert_ne!(grid.apply(&frame), Applied::NeedsSnapshot, "lost sync");
            }
            Some(Payload::Cursor(c)) if c.terminal == terminal => {
                grid.apply(&frame);
            }
            _ => {}
        }
    }
    states
}

fn replay_hub(name: &str) -> (Hub, Arc<ReplayCmux>, ExpectedFile) {
    let fixtures = fixtures_dir();
    let trace = Trace::read(&fixtures.join(format!("traces/{name}.xmtrace"))).unwrap();
    let expected: ExpectedFile = serde_json::from_slice(
        &std::fs::read(fixtures.join(format!("expected/{name}.grid.json"))).unwrap(),
    )
    .unwrap();
    let cmux = Arc::new(ReplayCmux::new(trace, Pace::Instant));
    (Hub::start(cmux.clone(), config()), cmux, expected)
}

/// Waits for the topology that lists `cmux_id` (replays create their terminal after the
/// session's first snapshot) and returns that terminal's xmux id.
async fn terminal_in(link: &mut ClientLink, cmux_id: &str) -> u32 {
    loop {
        let topology = topology(link).await;
        if let Some(t) = topology.terminals.iter().find(|t| t.cmux_id == cmux_id) {
            return t.id;
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn a_headset_gets_the_topology_then_rows_that_match_cmux() {
    let (hub, _, expected) = replay_hub("styles");
    let mut link = hub.join("quest1", 4096);
    let want = &expected.terminals[0];
    let terminal = terminal_in(&mut link, &want.cmux_id).await;
    send(&link, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid = Grid::new();
    let states = follow(&mut link, terminal, &mut grid, |g| {
        g.expected() == want.grid
    })
    .await;
    assert!(
        states.contains(&AttachStatus::Attached) || states.contains(&AttachStatus::Attaching),
        "{states:?}"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn two_headsets_share_the_terminal_and_a_late_one_starts_from_a_snapshot() {
    let (hub, _, expected) = replay_hub("burst");
    let want = &expected.terminals[0];
    let mut a = hub.join("a", 65536);
    let terminal = terminal_in(&mut a, &want.cmux_id).await;
    send(&a, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid_a = Grid::new();
    follow(&mut a, terminal, &mut grid_a, |g| g.expected() == want.grid).await;

    let mut b = hub.join("b", 65536);
    terminal_in(&mut b, &want.cmux_id).await;
    send(&b, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid_b = Grid::new();
    follow(&mut b, terminal, &mut grid_b, |g| g.expected() == want.grid).await;

    let status = hub.status().await;
    assert_eq!(status.attached_terminals, 1, "one shared attach stream");
    assert_eq!(status.clients.len(), 2);
}

#[tokio::test(flavor = "multi_thread")]
async fn input_maps_to_cmux_calls_with_stable_idempotency_keys() {
    let (hub, cmux, expected) = replay_hub("static80x24");
    let mut link = hub.join("quest-7f", 4096);
    let cmux_id = expected.terminals[0].cmux_id.clone();
    let terminal = terminal_in(&mut link, &cmux_id).await;
    let input = |idempotency, text: &str, keys: Vec<KeyPress>, mouse| {
        Payload::Input(Input {
            terminal,
            idempotency,
            text: text.into(),
            keys,
            mouse,
        })
    };
    send(&link, input(41, "ls\n", vec![], None));
    send(
        &link,
        input(
            42,
            "",
            vec![
                KeyPress {
                    key: "c".into(),
                    mods: 2,
                },
                KeyPress {
                    key: "Enter".into(),
                    mods: 0,
                },
            ],
            None,
        ),
    );
    send(
        &link,
        input(
            43,
            "",
            vec![],
            Some(MouseEvent {
                action: MouseAction::Scroll as i32,
                button: 0,
                row: 3,
                col: 9,
                mods: 1,
                dx: 0,
                dy: -2,
            }),
        ),
    );
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while cmux.inputs().len() < 3 {
        assert!(
            tokio::time::Instant::now() < deadline,
            "inputs: {:?}",
            cmux.inputs()
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let mut inputs = cmux.inputs();
    inputs.sort_by_key(|i| format!("{i:?}"));
    assert!(inputs.contains(&RecordedInput::Write {
        terminal: cmux_id.clone(),
        text: "ls\n".into(),
        key: "xmux-quest7f-41".into()
    }));
    assert!(inputs.contains(&RecordedInput::Keys {
        terminal: cmux_id.clone(),
        keys: vec!["ctrl+c".into(), "enter".into()],
        key: "xmux-quest7f-42".into()
    }));
    assert!(inputs.iter().any(|i| matches!(i,
        RecordedInput::Mouse { mouse: MouseInput { kind: MouseKind::Wheel, row: 3, col: 9, delta_rows: Some(-2), button: None, .. }, key, .. }
        if key == "xmux-quest7f-43")));
}

#[tokio::test(flavor = "multi_thread")]
async fn keystrokes_reach_cmux_in_the_order_they_were_typed() {
    let (hub, cmux, expected) = replay_hub("static80x24");
    let mut link = hub.join("q", 4096);
    let cmux_id = expected.terminals[0].cmux_id.clone();
    let terminal = terminal_in(&mut link, &cmux_id).await;
    let typed: Vec<String> = (0..200)
        .map(|i| char::from(b'a' + (i % 26) as u8).to_string())
        .collect();
    for (i, c) in typed.iter().enumerate() {
        send(
            &link,
            Payload::Input(Input {
                terminal,
                idempotency: i as u64 + 1,
                text: c.clone(),
                keys: vec![],
                mouse: None,
            }),
        );
    }
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while cmux.inputs().len() < typed.len() {
        assert!(tokio::time::Instant::now() < deadline);
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let got: Vec<String> = cmux
        .inputs()
        .into_iter()
        .map(|i| match i {
            RecordedInput::Write { text, .. } => text,
            other => panic!("{other:?}"),
        })
        .collect();
    assert_eq!(got, typed);
}

#[test]
fn key_chords_follow_cmux_syntax() {
    assert_eq!(key_chord("c", 2), "ctrl+c");
    assert_eq!(key_chord("Left", 1 | 4), "alt+shift+left");
    assert_eq!(key_chord("x", 8), "alt+x");
    assert_eq!(key_chord("f5", 0), "f5");
}

#[tokio::test(flavor = "multi_thread")]
async fn ping_gets_pong_and_resync_resends_a_full_snapshot() {
    let (hub, _, expected) = replay_hub("unicode");
    let want = &expected.terminals[0];
    let mut link = hub.join("q", 4096);
    let terminal = terminal_in(&mut link, &want.cmux_id).await;
    send(
        &link,
        Payload::Ping(Ping {
            nonce: 9,
            sent_ns: 123,
        }),
    );
    loop {
        if let Some(Payload::Pong(p)) = next(&mut link).await.payload {
            assert_eq!((p.nonce, p.sent_ns), (9, 123));
            break;
        }
    }
    send(&link, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid = Grid::new();
    follow(&mut link, terminal, &mut grid, |g| {
        g.expected() == want.grid
    })
    .await;
    send(&link, Payload::ResyncTerminal(ResyncTerminal { terminal }));
    loop {
        if let Some(Payload::Rows(r)) = next(&mut link).await.payload
            && r.terminal == terminal
        {
            assert!(r.full_reset);
            assert_eq!(r.dirty.len(), r.rows as usize);
            break;
        }
    }
}

// --- A scripted cmux for the cases a recording cannot produce. ---

enum Step {
    Item(RenderItem),
    End(&'static str),
}

#[derive(Default)]
struct Scripted {
    events: Mutex<Option<std_mpsc::Receiver<SessionItem>>>,
    attaches: Mutex<VecDeque<Vec<Step>>>,
    log: Arc<Mutex<Vec<String>>>,
}

struct ScriptedEvents(std_mpsc::Receiver<SessionItem>);

impl ItemStream<SessionItem> for ScriptedEvents {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<SessionItem>> {
        Ok(match self.0.recv_timeout(timeout) {
            Ok(item) => Poll::Item(item),
            Err(_) => Poll::TimedOut,
        })
    }
    fn cancel(&mut self) {}
}

struct ScriptedAttach {
    steps: VecDeque<Step>,
    log: Arc<Mutex<Vec<String>>>,
}

impl ItemStream<RenderItem> for ScriptedAttach {
    fn poll(&mut self, timeout: Duration) -> BackendResult<Poll<RenderItem>> {
        Ok(match self.steps.pop_front() {
            Some(Step::Item(item)) => Poll::Item(item),
            Some(Step::End(reason)) => Poll::End {
                reason: reason.into(),
            },
            None => {
                std::thread::sleep(timeout);
                Poll::TimedOut
            }
        })
    }
    fn cancel(&mut self) {
        self.log.lock().unwrap().push("cancel".into());
    }
}

impl Attachment for ScriptedAttach {
    fn resize(&mut self, size: Size) -> BackendResult<()> {
        self.log
            .lock()
            .unwrap()
            .push(format!("resize {}x{}", size.cols, size.rows));
        Ok(())
    }
    fn release(&mut self) -> BackendResult<()> {
        self.log.lock().unwrap().push("release".into());
        Ok(())
    }
}

impl Cmux for Scripted {
    fn session_name(&self) -> String {
        "scripted".into()
    }
    fn events(&self) -> BackendResult<Box<dyn ItemStream<SessionItem>>> {
        let rx = self
            .events
            .lock()
            .unwrap()
            .take()
            .expect("events opened once");
        Ok(Box::new(ScriptedEvents(rx)))
    }
    fn attach(&self, terminal: &str) -> BackendResult<Box<dyn Attachment>> {
        self.log.lock().unwrap().push(format!("attach {terminal}"));
        let steps = self
            .attaches
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or_default();
        Ok(Box::new(ScriptedAttach {
            steps: steps.into(),
            log: self.log.clone(),
        }))
    }
    fn write(&self, _: &str, _: &str, _: &str) -> BackendResult<()> {
        Ok(())
    }
    fn keys(&self, _: &str, _: &[String], _: &str) -> BackendResult<()> {
        Ok(())
    }
    fn mouse(&self, _: &str, _: &MouseInput, _: &str) -> BackendResult<()> {
        Ok(())
    }
    fn focus_direction(&self, pane: &PanePath, direction: Direction, _: &str) -> BackendResult<()> {
        self.log
            .lock()
            .unwrap()
            .push(format!("focus {} {direction:?}", pane.pane));
        Ok(())
    }
    fn focus_pane(&self, pane: &PanePath, _: &str) -> BackendResult<()> {
        self.log
            .lock()
            .unwrap()
            .push(format!("focus pane {}", pane.pane));
        Ok(())
    }
    fn focus_tab(&self, pane: &PanePath, tab: &str, _: &str) -> BackendResult<()> {
        self.log
            .lock()
            .unwrap()
            .push(format!("focus tab {tab} in {}", pane.pane));
        Ok(())
    }
}

fn session() -> SessionDoc {
    SessionDoc {
        name: Some("scripted".into()),
        workspaces: vec![WorkspaceDoc {
            id: "ws_1".into(),
            name: "main".into(),
            index: 0,
            focused: true,
        }],
        screens: vec![ScreenDoc {
            id: "screen_1".into(),
            workspace_id: "ws_1".into(),
            name: None,
            index: 0,
            focused: true,
            layout: Layout::Leaf {
                pane_id: "pane_1".into(),
                tab_ids: vec!["tab_1".into()],
                active_tab_id: Some("tab_1".into()),
            },
        }],
        panes: vec![PaneDoc {
            id: "pane_1".into(),
            screen_id: "screen_1".into(),
            name: None,
            focused: true,
        }],
        tabs: vec![TabDoc {
            id: "tab_1".into(),
            pane_id: "pane_1".into(),
            name: None,
            index: 0,
            focused: true,
            terminal_id: Some("term_1".into()),
        }],
        terminals: vec![TerminalDoc {
            id: "term_1".into(),
            title: "sh".into(),
            cols: 4,
            rows: 1,
            running: true,
        }],
    }
}

fn snapshot(text: &str) -> Step {
    Step::Item(RenderItem::Snapshot {
        size: Size { cols: 4, rows: 1 },
        cursor: RenderCursor::default(),
        default_fg: "#ffffff".into(),
        default_bg: "#000000".into(),
        rows: vec![RenderRow {
            row: 0,
            runs: vec![RenderRun {
                text: text.into(),
                ..Default::default()
            }],
        }],
    })
}

fn scripted(
    attaches: Vec<Vec<Step>>,
) -> (Hub, std_mpsc::Sender<SessionItem>, Arc<Mutex<Vec<String>>>) {
    let (tx, rx) = std_mpsc::channel();
    let cmux = Scripted {
        events: Mutex::new(Some(rx)),
        attaches: Mutex::new(attaches.into()),
        log: Arc::default(),
    };
    let log = cmux.log.clone();
    tx.send(SessionItem::Snapshot { session: session() })
        .unwrap();
    (Hub::start(Arc::new(cmux), config()), tx, log)
}

async fn wait_for(log: &Arc<Mutex<Vec<String>>>, entry: &str) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while !log.lock().unwrap().iter().any(|l| l == entry) {
        assert!(
            tokio::time::Instant::now() < deadline,
            "{entry:?} not in {:?}",
            log.lock().unwrap()
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn a_stream_gap_reattaches_and_starts_a_new_generation() {
    let (hub, _events, log) = scripted(vec![
        vec![snapshot("old"), Step::End("gap")],
        vec![snapshot("new")],
    ]);
    let mut link = hub.join("q", 4096);
    let t = topology(&mut link).await;
    let terminal = t.terminals[0].id;
    send(&link, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid = Grid::new();
    let states = follow(&mut link, terminal, &mut grid, |g| g.row_text(0) == "new ").await;
    assert!(states.contains(&AttachStatus::Stalled), "{states:?}");
    assert_eq!(
        log.lock()
            .unwrap()
            .iter()
            .filter(|l| l.starts_with("attach"))
            .count(),
        2
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn resize_is_opt_in_and_detach_releases_the_viewer() {
    let (hub, _events, log) = scripted(vec![vec![snapshot("abc")]]);
    let mut link = hub.join("q", 4096);
    let t = topology(&mut link).await;
    let terminal = t.terminals[0].id;
    send(&link, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid = Grid::new();
    follow(&mut link, terminal, &mut grid, |g| g.row_text(0) == "abc ").await;
    assert!(
        !log.lock().unwrap().iter().any(|l| l.starts_with("resize")),
        "attaching never resizes"
    );
    send(
        &link,
        Payload::Resize(Resize {
            terminal,
            cols: 100,
            rows: 40,
        }),
    );
    wait_for(&log, "resize 100x40").await;
    send(&link, Payload::DetachTerminal(DetachTerminal { terminal }));
    wait_for(&log, "release").await;
    wait_for(&log, "cancel").await;
}

#[tokio::test(flavor = "multi_thread")]
async fn focus_direction_targets_the_screens_focused_pane() {
    let (hub, _events, log) = scripted(vec![]);
    let mut link = hub.join("q", 4096);
    let t = topology(&mut link).await;
    let screen = t.screens[0].id;
    send(
        &link,
        Payload::FocusDirection(xmux_proto::FocusDirection {
            screen,
            direction: xmux_proto::Direction::Right as i32,
        }),
    );
    wait_for(&log, "focus pane_1 Right").await;
    let pane = t.screens[0].panes[0].id;
    let tab = t.screens[0].panes[0].tabs[0].id;
    send(&link, Payload::FocusPane(xmux_proto::FocusPane { pane }));
    wait_for(&log, "focus pane pane_1").await;
    send(&link, Payload::FocusTab(xmux_proto::FocusTab { tab }));
    wait_for(&log, "focus tab tab_1 in pane_1").await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_closed_terminal_is_reported_detached() {
    let (hub, events, _log) = scripted(vec![vec![snapshot("abc")]]);
    let mut link = hub.join("q", 4096);
    let t = topology(&mut link).await;
    let terminal = t.terminals[0].id;
    send(&link, Payload::AttachTerminal(AttachTerminal { terminal }));
    let mut grid = Grid::new();
    follow(&mut link, terminal, &mut grid, |g| g.row_text(0) == "abc ").await;
    events
        .send(SessionItem::Delta {
            changes: vec![Change::Delete {
                resource: ResourceKind::Terminal,
                id: "term_1".into(),
            }],
        })
        .unwrap();
    loop {
        if let Some(Payload::AttachState(a)) = next(&mut link).await.payload
            && a.terminal == terminal
            && a.status == AttachStatus::Detached as i32
        {
            break;
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn an_unknown_terminal_is_an_attach_error() {
    let (hub, _events, _log) = scripted(vec![]);
    let mut link = hub.join("q", 4096);
    topology(&mut link).await;
    send(
        &link,
        Payload::AttachTerminal(AttachTerminal { terminal: 999 }),
    );
    loop {
        if let Some(Payload::AttachState(a)) = next(&mut link).await.payload {
            assert_eq!((a.terminal, a.status), (999, AttachStatus::Error as i32));
            break;
        }
    }
}

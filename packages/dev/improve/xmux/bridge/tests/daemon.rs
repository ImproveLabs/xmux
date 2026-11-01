//! The hub against a real cmux daemon (a sandboxed server per test). Needs the `cmux` CLI:
//! `cargo test -p xmux-bridge --features daemon --test daemon`.

#![cfg(feature = "daemon")]

use std::sync::Arc;
use std::time::Duration;

use cmux::{
    Client, Config, CreateWorkspaceOptions, InitialContent, MutationOptions, RunCommand,
    RunOptions, SplitOptions,
};
use xmux_bridge::grid::{Applied, Grid};
use xmux_bridge::hub::{ClientLink, Hub, HubConfig};
use xmux_bridge::sandbox::{SandboxServer, find_cmux};
use xmux_bridge::sdk::SdkCmux;
use xmux_proto::frame::Payload;
use xmux_proto::{
    AttachStatus, AttachTerminal, Frame, Input, KeyPress, Resize, Topology, decode_frame,
};

struct Fixture {
    server: SandboxServer,
    client: Client,
    terminal: String,
    workspace: cmux::Workspace,
    first: cmux::Created<cmux::Terminal>,
}

/// A sandboxed server with one 80x24 `/bin/sh` terminal.
fn fixture(name: &str) -> Fixture {
    let server =
        SandboxServer::start(&find_cmux(), &format!("xmux-it-{name}")).expect("cmux server");
    let client = Client::connect(Config::from_socket_path(&server.socket)).unwrap();
    let workspace = client
        .current_session()
        .create_workspace_with(
            CreateWorkspaceOptions {
                name: Some(name.into()),
                initial_content: InitialContent::Empty,
                correlation_key: None,
            },
            MutationOptions::unique().unwrap(),
        )
        .unwrap()
        .resource;
    let first = workspace
        .run_with(
            RunOptions {
                command: RunCommand::argv(["/bin/sh"]).unwrap(),
                cwd: Some(server.dir_string()),
                name: None,
                size: Some(cmux::Size::new(80, 24).unwrap()),
                correlation_key: None,
            },
            MutationOptions::unique().unwrap(),
        )
        .unwrap();
    let terminal = first.value.terminal_id().unwrap().as_str().to_string();
    Fixture {
        server,
        client,
        terminal,
        workspace,
        first,
    }
}

async fn next(link: &mut ClientLink) -> Frame {
    let wire = tokio::time::timeout(Duration::from_secs(30), link.outgoing.recv())
        .await
        .expect("frame")
        .expect("hub");
    decode_frame(&wire).unwrap().1
}

async fn topology_where(link: &mut ClientLink, ok: impl Fn(&Topology) -> bool) -> Topology {
    loop {
        if let Some(Payload::Topology(t)) = next(link).await.payload
            && ok(&t)
        {
            return t;
        }
    }
}

async fn until(
    link: &mut ClientLink,
    terminal: u32,
    grid: &mut Grid,
    done: impl Fn(&Grid) -> bool,
) {
    while !(grid.is_synced() && done(grid)) {
        let frame = next(link).await;
        match &frame.payload {
            Some(Payload::Rows(r)) if r.terminal == terminal => {
                assert_ne!(grid.apply(&frame), Applied::NeedsSnapshot);
            }
            Some(Payload::Cursor(c)) if c.terminal == terminal => {
                grid.apply(&frame);
            }
            _ => {}
        }
    }
}

fn contains(grid: &Grid, needle: &str) -> bool {
    (0..grid.rows).any(|r| grid.row_text(r).contains(needle))
}

fn hub(f: &Fixture) -> Hub {
    let cmux = Arc::new(SdkCmux::connect(&f.server.session, Some(&f.server.socket)).unwrap());
    Hub::start(cmux, HubConfig::default())
}

#[tokio::test(flavor = "multi_thread")]
async fn typed_input_echoes_and_attaching_never_resizes_the_terminal() {
    let f = fixture("echo");
    let hub = hub(&f);
    let mut link = hub.join("it", 65536);
    let topology = topology_where(&mut link, |t| {
        t.terminals.iter().any(|x| x.cmux_id == f.terminal)
    })
    .await;
    let terminal = topology
        .terminals
        .iter()
        .find(|t| t.cmux_id == f.terminal)
        .unwrap()
        .id;
    link.send(Frame {
        payload: Some(Payload::AttachTerminal(AttachTerminal { terminal })),
    });
    let mut grid = Grid::new();
    until(&mut link, terminal, &mut grid, |g| g.cols == 80).await;

    link.send(Frame {
        payload: Some(Payload::Input(Input {
            terminal,
            idempotency: 1,
            text: "echo hi-from-$((40+2))".into(),
            ..Default::default()
        })),
    });
    link.send(Frame {
        payload: Some(Payload::Input(Input {
            terminal,
            idempotency: 2,
            keys: vec![KeyPress {
                key: "enter".into(),
                mods: 0,
            }],
            ..Default::default()
        })),
    });
    until(&mut link, terminal, &mut grid, |g| {
        contains(g, "hi-from-42")
    })
    .await;

    let snapshot = f.first.resource.refresh().unwrap();
    assert_eq!(
        (snapshot.cols, snapshot.rows),
        (80, 24),
        "a headset viewer must not resize the Mac's grid"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn opt_in_resize_changes_the_grid() {
    let f = fixture("resize");
    let hub = hub(&f);
    let mut link = hub.join("it", 65536);
    let topology = topology_where(&mut link, |t| {
        t.terminals.iter().any(|x| x.cmux_id == f.terminal)
    })
    .await;
    let terminal = topology
        .terminals
        .iter()
        .find(|t| t.cmux_id == f.terminal)
        .unwrap()
        .id;
    link.send(Frame {
        payload: Some(Payload::AttachTerminal(AttachTerminal { terminal })),
    });
    let mut grid = Grid::new();
    until(&mut link, terminal, &mut grid, |g| g.cols == 80).await;
    link.send(Frame {
        payload: Some(Payload::Resize(Resize {
            terminal,
            cols: 100,
            rows: 30,
        })),
    });
    until(&mut link, terminal, &mut grid, |g| {
        (g.cols, g.rows) == (100, 30)
    })
    .await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_split_shows_up_in_the_topology() {
    let f = fixture("split");
    let hub = hub(&f);
    let mut link = hub.join("it", 65536);
    topology_where(&mut link, |t| {
        t.terminals.iter().any(|x| x.cmux_id == f.terminal)
    })
    .await;
    let pane = f
        .workspace
        .screen(f.first.value.screen_id().cloned().unwrap())
        .pane(f.first.value.pane_id().cloned().unwrap());
    pane.split(SplitOptions {
        direction: cmux::Direction::Down,
        ratio: Some(0.5),
        viewport_width: None,
        cwd: Some(f.server.dir_string()),
        size: None,
        correlation_key: None,
    })
    .unwrap();
    let t = topology_where(&mut link, |t| t.screens.iter().any(|s| s.panes.len() == 2)).await;
    let screen = t.screens.iter().find(|s| s.panes.len() == 2).unwrap();
    let layout = screen.layout.as_ref().unwrap();
    assert_eq!(layout.kind, xmux_proto::NodeKind::Split as i32);
    assert_eq!(
        layout.direction,
        xmux_proto::SplitDirection::Vertical as i32,
        "a down split stacks panes"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn a_disconnected_headset_releases_its_viewer() {
    let f = fixture("release");
    let hub = hub(&f);
    let mut link = hub.join("it", 65536);
    let topology = topology_where(&mut link, |t| {
        t.terminals.iter().any(|x| x.cmux_id == f.terminal)
    })
    .await;
    let terminal = topology
        .terminals
        .iter()
        .find(|t| t.cmux_id == f.terminal)
        .unwrap()
        .id;
    link.send(Frame {
        payload: Some(Payload::AttachTerminal(AttachTerminal { terminal })),
    });
    loop {
        if let Some(Payload::AttachState(a)) = next(&mut link).await.payload
            && a.status == AttachStatus::Attached as i32
        {
            break;
        }
    }
    let attached = || -> usize {
        f.client
            .current_session()
            .snapshot()
            .map(|s| {
                s.clients
                    .iter()
                    .filter(|c| {
                        c.attached_terminal_ids
                            .iter()
                            .any(|t| t.as_str() == f.terminal)
                    })
                    .count()
            })
            .unwrap_or(0)
    };
    assert_eq!(attached(), 1);
    drop(link);
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while attached() != 0 {
        assert!(
            std::time::Instant::now() < deadline,
            "the viewer was not released"
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

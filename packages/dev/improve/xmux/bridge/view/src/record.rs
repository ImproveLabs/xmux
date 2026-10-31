//! Records conformance scenarios. Each run starts its own sandboxed cmux server (see
//! `xmux_bridge::sandbox`), so traces contain nothing from the machine they were recorded on,
//! then drives one scenario per fresh workspace.
//!
//! For every scenario the recorder captures the session events and one attach stream per
//! terminal into `traces/<name>.xmtrace`, waits until output settles, and stores what a fresh
//! attachment reports as `expected/<name>.grid.json`. Replaying the trace must reproduce it.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use cmux::{
    Client, Config, CreateWorkspaceOptions, InitialContent, MutationOptions, RunCommand,
    RunOptions, Size as CmuxSize, SplitOptions,
};
use serde_json::{Map, Value};
use xmux_bridge::backend::{Cmux, Poll};
use xmux_bridge::conformance::{ExpectedFile, ExpectedTerminal, replay};
use xmux_bridge::grid::Grid;
use xmux_bridge::model::{RenderItem, Size};
use xmux_bridge::sandbox::{SandboxServer, find_cmux};
use xmux_bridge::sdk::SdkCmux;
use xmux_bridge::terminal::TerminalState;
use xmux_bridge::trace::{Trace, TraceHeader, TraceWriter};

/// A step after the first terminal starts.
enum Step {
    Sleep(u64),
    /// A second viewer reports this size, which makes cmux resize the terminal (latest-wins).
    ResizeFirst(u16, u16),
    /// Splits the first terminal's pane to the right and runs `script` in the new pane.
    SplitRight(&'static str),
    /// Adds a tab running `script` to the first terminal's pane.
    NewTab(&'static str),
}

struct Scenario {
    name: &'static str,
    script: &'static str,
    steps: Vec<Step>,
}

fn scenarios() -> Vec<Scenario> {
    vec![
        Scenario {
            name: "static80x24",
            script: "sleep 1; i=1; while [ $i -le 24 ]; do printf '%02d %.77s' $i \
                     'The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'; \
                     [ $i -lt 24 ] && printf '\\n'; i=$((i+1)); done; sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "scroll",
            script: "sleep 1; i=1; while [ $i -le 120 ]; do echo \"line $i of the scroll test\"; \
                     [ $((i % 10)) -eq 0 ] && sleep 0.05; i=$((i+1)); done; sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "styles",
            script: r"sleep 1
printf '\033[1mbold\033[0m \033[2mdim\033[0m \033[3mitalic\033[0m \033[4munder\033[0m \033[4:2mdouble\033[0m \033[4:3mcurly\033[0m \033[4:4mdotted\033[0m \033[4:5mdashed\033[0m\n'
printf '\033[5mblink\033[0m \033[7minverse\033[0m [\033[8minvisible\033[0m] \033[9mstrike\033[0m \033[1;3;4mcombo\033[0m\n'
for c in 30 31 32 33 34 35 36 37 90 91 92 93 94 95 96 97; do printf '\033[%sm%s\033[0m ' $c $c; done; printf '\n'
for c in 40 41 42 43 44 45 46 47 100 101 102 103 104 105 106 107; do printf '\033[%sm%s\033[0m ' $c $c; done; printf '\n'
i=16; while [ $i -lt 76 ]; do printf '\033[38;5;%dm#\033[0m' $i; i=$((i+1)); done; printf '\n'
printf '\033[38;2;255;100;0mtruecolor fg\033[0m \033[48;2;0;80;160mtruecolor bg\033[0m \033[38;2;10;200;90;48;2;40;40;40mboth\033[0m\n'
sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "unicode",
            script: "sleep 1
printf 'CJK: 你好世界 日本語 한국어\\n'
printf 'emoji: 🙂 🚀 👍🏽 👨‍👩‍👧 🇨🇦\\n'
printf 'combining: e\\314\\201 n\\314\\203 a\\314\\210\\n'
printf 'box: ┌──┬──┐ │a │b │ └──┴──┘\\n'
printf 'mixed: a你b🙂c\\n'
sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "cursor",
            script: r"sleep 1
printf 'top-left'
printf '\033[5;10Hat 5,10'
printf '\033[10;1Hline 10 will be cleared\033[10;1H\033[2Kcleared line 10'
printf '\033[s\033[15;30Hsaved-and-moved\033[u!'
printf '\033[6 q'
sleep 0.5
printf '\033[20;5Hcursor ends here'
printf '\033[?25l'
sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "resize80to120",
            script: "sleep 1; printf 'before resize\\n'; sleep 3; printf 'after resize: '; stty size; sleep 600",
            steps: vec![
                Step::Sleep(2000),
                Step::ResizeFirst(120, 30),
                Step::Sleep(3000),
            ],
        },
        Scenario {
            name: "split2panes",
            script: "sleep 1; printf 'left pane\\n'; sleep 3; printf 'left again\\n'; sleep 600",
            steps: vec![
                Step::Sleep(500),
                Step::SplitRight(
                    "sleep 1; printf 'right pane\\n'; sleep 1; printf 'right again\\n'; sleep 600",
                ),
                Step::Sleep(3500),
            ],
        },
        Scenario {
            name: "tabs",
            script: "sleep 1; printf 'first tab\\n'; sleep 600",
            steps: vec![
                Step::Sleep(500),
                Step::NewTab("sleep 1; printf 'second tab\\n'; sleep 600"),
                Step::Sleep(2500),
            ],
        },
        Scenario {
            name: "full_reset",
            script: r"sleep 1; printf 'before alt screen\n'; sleep 0.5
printf '\033[?1049h\033[H\033[2Jinside alt screen\n'; sleep 0.5
printf '\033[?1049l'; sleep 0.5; printf 'after alt screen\n'; sleep 0.5
printf '\033c'; printf 'after RIS\n'; sleep 600",
            steps: vec![],
        },
        Scenario {
            name: "burst",
            script: "sleep 1; i=0; while [ $i -lt 3000 ]; do \
                     echo \"burst $i: the quick brown fox jumps over the lazy dog\"; i=$((i+1)); done; \
                     printf 'burst done'; sleep 600",
            steps: vec![],
        },
    ]
}

pub fn run(cmux: Option<PathBuf>, fixtures: &Path, names: &[String]) -> Result<()> {
    let all = scenarios();
    let selected: Vec<&Scenario> = if names.is_empty() {
        all.iter().collect()
    } else {
        names
            .iter()
            .map(|n| {
                all.iter()
                    .find(|s| s.name == n)
                    .ok_or_else(|| anyhow!("unknown scenario {n}"))
            })
            .collect::<Result<_>>()?
    };
    let bin = cmux.unwrap_or_else(find_cmux);
    let server =
        SandboxServer::start(&bin, "xmux-rec").context("starting a sandboxed cmux server")?;
    std::fs::create_dir_all(fixtures.join("traces"))?;
    std::fs::create_dir_all(fixtures.join("expected"))?;
    for scenario in selected {
        eprintln!("recording {}", scenario.name);
        record(&server, scenario, fixtures)
            .with_context(|| format!("scenario {}", scenario.name))?;
    }
    Ok(())
}

type Server = SandboxServer;

/// Records one raw attach stream until `stop`, bumping `last` on every item.
fn attach_thread(
    backend: Arc<SdkCmux>,
    writer: Arc<TraceWriter>,
    terminal: String,
    sandbox: String,
    stop: Arc<AtomicBool>,
    last: Arc<AtomicU64>,
    origin: Instant,
) -> JoinHandle<Result<()>> {
    thread::spawn(move || {
        let mut params = Map::new();
        params.insert("terminal".into(), Value::String(terminal.clone()));
        let mut stream = backend.raw_stream("terminal.attach", params)?;
        while !stop.load(Ordering::Relaxed) {
            match stream.poll(Duration::from_millis(50))? {
                Poll::Item(item) => {
                    writer.attach(&terminal, &scrub(item, &sandbox))?;
                    last.store(origin.elapsed().as_millis() as u64, Ordering::Relaxed);
                }
                Poll::TimedOut => {}
                Poll::End { reason } => bail!("attach stream for {terminal} ended: {reason}"),
            }
        }
        stream.close();
        Ok(())
    })
}

/// Replaces the sandbox path (terminal cwds) so traces do not depend on where they were made.
fn scrub(item: Value, sandbox: &str) -> Value {
    match item {
        Value::String(s) if s.contains(sandbox) => Value::String(s.replace(sandbox, "/sandbox")),
        Value::Array(a) => Value::Array(a.into_iter().map(|v| scrub(v, sandbox)).collect()),
        Value::Object(o) => {
            Value::Object(o.into_iter().map(|(k, v)| (k, scrub(v, sandbox))).collect())
        }
        other => other,
    }
}

fn terminal_of(created: &cmux::Created<cmux::Terminal>) -> Result<String> {
    Ok(created
        .value
        .terminal_id()
        .ok_or_else(|| anyhow!("run returned no terminal"))?
        .as_str()
        .to_string())
}

fn record(server: &Server, scenario: &Scenario, fixtures: &Path) -> Result<()> {
    let client = Client::connect(Config::from_socket_path(&server.socket))?;
    let backend = Arc::new(SdkCmux::connect(&server.session, Some(&server.socket))?);
    let session = client.current_session();
    let size = CmuxSize::new(80, 24)?;
    let run = |script: &str| -> Result<RunOptions> {
        Ok(RunOptions {
            command: RunCommand::argv(["/bin/sh", "-c", script])?,
            cwd: Some(server.dir_string()),
            name: None,
            size: Some(size),
            correlation_key: None,
        })
    };

    let sandbox = server.dir_string();
    let trace_path = fixtures
        .join("traces")
        .join(format!("{}.xmtrace", scenario.name));
    let header = TraceHeader {
        scenario: scenario.name.to_string(),
        cmux: server.version.clone(),
        recorded: utc_now(),
    };
    let writer = Arc::new(TraceWriter::create(&trace_path, &header)?);
    let stop = Arc::new(AtomicBool::new(false));
    let origin = Instant::now();
    let last = Arc::new(AtomicU64::new(0));

    let events = {
        let (backend, writer, stop) = (backend.clone(), writer.clone(), stop.clone());
        let sandbox = sandbox.clone();
        thread::spawn(move || -> Result<()> {
            let mut stream = backend.raw_stream("session.events", Map::new())?;
            while !stop.load(Ordering::Relaxed) {
                if let Poll::Item(item) = stream.poll(Duration::from_millis(50))? {
                    writer.events(&scrub(item, &sandbox))?;
                }
            }
            stream.close();
            Ok(())
        })
    };
    // Let the events stream deliver its initial snapshot before anything changes.
    thread::sleep(Duration::from_millis(300));

    let workspace = session
        .create_workspace_with(
            CreateWorkspaceOptions {
                name: Some(format!("rec-{}", scenario.name)),
                initial_content: InitialContent::Empty,
                correlation_key: None,
            },
            MutationOptions::unique()?,
        )?
        .resource;
    let first = workspace.run_with(run(scenario.script)?, MutationOptions::unique()?)?;
    let first_id = terminal_of(&first)?;
    let first_pane = first
        .value
        .pane_id()
        .cloned()
        .ok_or_else(|| anyhow!("run returned no pane"))?;
    let mut terminals = vec![first_id.clone()];
    let mut attaches = vec![attach_thread(
        backend.clone(),
        writer.clone(),
        first_id.clone(),
        sandbox.clone(),
        stop.clone(),
        last.clone(),
        origin,
    )];
    let mut resizer = None;

    for step in &scenario.steps {
        match step {
            Step::Sleep(ms) => thread::sleep(Duration::from_millis(*ms)),
            Step::ResizeFirst(cols, rows) => {
                let mut viewer = backend.attach(&first_id)?;
                viewer.resize(Size {
                    cols: *cols,
                    rows: *rows,
                })?;
                resizer = Some(viewer);
            }
            Step::SplitRight(script) => {
                let screen = workspace.screen(first.value.screen_id().cloned().unwrap());
                let pane = screen.pane(first_pane.clone());
                let created = pane.split(SplitOptions {
                    direction: cmux::Direction::Right,
                    ratio: Some(0.5),
                    viewport_width: None,
                    cwd: Some(server.dir_string()),
                    size: None,
                    correlation_key: None,
                })?;
                let new_pane = created.resource;
                let shells: Vec<cmux::Tab> = new_pane.tabs()?;
                let ran = new_pane.run_with(run(script)?, MutationOptions::unique()?)?;
                // The split starts with a shell tab; keep only the scripted one.
                for tab in shells {
                    tab.close()?;
                }
                let id = terminal_of(&ran)?;
                terminals.push(id.clone());
                attaches.push(attach_thread(
                    backend.clone(),
                    writer.clone(),
                    id,
                    sandbox.clone(),
                    stop.clone(),
                    last.clone(),
                    origin,
                ));
            }
            Step::NewTab(script) => {
                let screen = workspace.screen(first.value.screen_id().cloned().unwrap());
                let ran = screen
                    .pane(first_pane.clone())
                    .run_with(run(script)?, MutationOptions::unique()?)?;
                let id = terminal_of(&ran)?;
                terminals.push(id.clone());
                attaches.push(attach_thread(
                    backend.clone(),
                    writer.clone(),
                    id,
                    sandbox.clone(),
                    stop.clone(),
                    last.clone(),
                    origin,
                ));
            }
        }
    }

    // Wait for output to settle: 1.5 s without items (scripts all end in a long sleep).
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let quiet = origin.elapsed().as_millis() as u64 - last.load(Ordering::Relaxed);
        if quiet > 1500 && origin.elapsed() > Duration::from_secs(3) {
            break;
        }
        if Instant::now() > deadline {
            bail!("output did not settle");
        }
        thread::sleep(Duration::from_millis(100));
    }

    // What cmux reports to a fresh viewer is the ground truth.
    let mut expected = Vec::new();
    for (i, id) in terminals.iter().enumerate() {
        let mut fresh = backend.attach(id)?;
        let item = loop {
            match fresh.poll(Duration::from_secs(5))? {
                Poll::Item(item @ RenderItem::Snapshot { .. }) => break item,
                Poll::Item(_) => continue,
                other => bail!("no snapshot for {id}: {other:?}"),
            }
        };
        fresh.cancel();
        let mut state = TerminalState::new(i as u32 + 1);
        state.apply(&item);
        let mut grid = Grid::new();
        for frame in state.take_frames() {
            grid.apply(&frame);
        }
        expected.push(ExpectedTerminal {
            cmux_id: id.clone(),
            grid: grid.expected(),
        });
    }

    stop.store(true, Ordering::Relaxed);
    if let Some(mut viewer) = resizer {
        let _ = viewer.release();
        viewer.cancel();
    }
    for a in attaches {
        a.join().map_err(|_| anyhow!("attach thread panicked"))??;
    }
    events
        .join()
        .map_err(|_| anyhow!("events thread panicked"))??;
    Arc::try_unwrap(writer)
        .map_err(|_| anyhow!("trace writer still shared"))?
        .finish()?;
    workspace.close()?;
    drop(client);

    let file = ExpectedFile {
        scenario: scenario.name.to_string(),
        terminals: expected,
    };
    let expected_path = fixtures
        .join("expected")
        .join(format!("{}.grid.json", scenario.name));
    std::fs::write(&expected_path, serde_json::to_string_pretty(&file)? + "\n")?;

    // Sanity check right away: the trace must replay to the expected grids.
    let replayed = replay(&Trace::read(&trace_path)?);
    for (want, got) in file.terminals.iter().zip(&replayed.terminals) {
        if want != got {
            eprintln!(
                "  warning: replay of {} differs from cmux's snapshot for {}",
                scenario.name, want.cmux_id
            );
        }
    }
    Ok(())
}

/// `YYYY-MM-DDTHH:MM:SSZ` without a date library.
fn utc_now() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (days, rem) = (secs / 86_400, secs % 86_400);
    // Civil-from-days (Howard Hinnant).
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

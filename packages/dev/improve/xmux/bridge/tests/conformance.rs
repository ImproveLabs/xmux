//! Every recorded trace replays to the grids cmux reported at the end of its recording, and its
//! frame stream matches the checked-in `frames/<name>.xmframes` the Kotlin client replays.
//! `XMUX_UPDATE_GOLDENS=1 cargo test -p xmux-bridge --test conformance` rewrites the streams.

use std::path::PathBuf;

use xmux_bridge::conformance::{ExpectedFile, decode_stream, fixtures_dir, replay};
use xmux_bridge::trace::Trace;
use xmux_proto::Topology;

fn traces() -> Vec<PathBuf> {
    let mut paths: Vec<PathBuf> = std::fs::read_dir(fixtures_dir().join("traces"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "xmtrace"))
        .collect();
    paths.sort();
    paths
}

fn name(path: &std::path::Path) -> String {
    path.file_stem().unwrap().to_string_lossy().into_owned()
}

#[test]
fn all_ten_scenarios_are_recorded() {
    let names: Vec<String> = traces().iter().map(|p| name(p)).collect();
    for scenario in [
        "burst",
        "cursor",
        "full_reset",
        "resize80to120",
        "scroll",
        "split2panes",
        "static80x24",
        "styles",
        "tabs",
        "unicode",
    ] {
        assert!(
            names.iter().any(|n| n == scenario),
            "missing trace {scenario}"
        );
    }
}

#[test]
fn traces_replay_to_the_grids_cmux_reported() {
    for path in traces() {
        let name = name(&path);
        let trace = Trace::read(&path).unwrap();
        let expected: ExpectedFile = serde_json::from_slice(
            &std::fs::read(
                fixtures_dir()
                    .join("expected")
                    .join(format!("{name}.grid.json")),
            )
            .unwrap(),
        )
        .unwrap();
        let replayed = replay(&trace);
        assert_eq!(
            replayed.terminals.len(),
            expected.terminals.len(),
            "{name}: terminal count"
        );
        for (want, got) in expected.terminals.iter().zip(&replayed.terminals) {
            assert_eq!(want.cmux_id, got.cmux_id, "{name}");
            if want.grid != got.grid {
                for (i, (w, g)) in want.grid.lines.iter().zip(&got.grid.lines).enumerate() {
                    if w != g {
                        eprintln!("{name} row {i}:\n  want |{w}|\n  got  |{g}|");
                    }
                }
                panic!("{name}: {} replays to a different grid", want.cmux_id);
            }
        }
    }
}

#[test]
fn frame_streams_match_the_checked_in_goldens() {
    let update = std::env::var("XMUX_UPDATE_GOLDENS").is_ok();
    for path in traces() {
        let name = name(&path);
        let stream = replay(&Trace::read(&path).unwrap()).stream;
        decode_stream(&stream).expect("the replay stream decodes");
        let golden = fixtures_dir()
            .join("frames")
            .join(format!("{name}.xmframes"));
        if update {
            std::fs::write(&golden, &stream).unwrap();
        } else {
            let want =
                std::fs::read(&golden).unwrap_or_else(|_| panic!("missing {}", golden.display()));
            assert!(
                want == stream,
                "{name}: frame stream changed; rerun with XMUX_UPDATE_GOLDENS=1"
            );
        }
    }
}

/// The topology frame for a scenario, reduced to what layout code reads.
fn layout_summary(topology: &Topology) -> Vec<String> {
    let mut out = Vec::new();
    for screen in &topology.screens {
        let layout = screen.layout.as_ref().unwrap();
        out.push(format!(
            "screen {} kind={} direction={} children={} focused_pane={}",
            screen.id,
            layout.kind,
            layout.direction,
            layout.children.len(),
            screen.focused_pane
        ));
        for pane in &screen.panes {
            let tabs: Vec<String> = pane
                .tabs
                .iter()
                .map(|t| format!("tab {} -> terminal {}", t.id, t.terminal))
                .collect();
            out.push(format!(
                "  pane {} active_tab={} [{}]",
                pane.id,
                pane.active_tab,
                tabs.join(", ")
            ));
        }
    }
    out
}

#[test]
fn split_and_tabs_show_up_in_the_topology() {
    let fixtures = fixtures_dir().join("traces");
    let split = replay(&Trace::read(&fixtures.join("split2panes.xmtrace")).unwrap())
        .topology
        .unwrap();
    let screen = split
        .screens
        .iter()
        .find(|s| s.panes.len() == 2)
        .expect("a screen with two panes");
    let layout = screen.layout.as_ref().unwrap();
    assert_eq!(
        layout.kind,
        xmux_proto::NodeKind::Split as i32,
        "{:#?}",
        layout_summary(&split)
    );
    assert_eq!(layout.children.len(), 2);

    let tabs = replay(&Trace::read(&fixtures.join("tabs.xmtrace")).unwrap())
        .topology
        .unwrap();
    let pane = tabs
        .screens
        .iter()
        .flat_map(|s| &s.panes)
        .find(|p| p.tabs.len() == 2);
    assert!(pane.is_some(), "{:#?}", layout_summary(&tabs));
}

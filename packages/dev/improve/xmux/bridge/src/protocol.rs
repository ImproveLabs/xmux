//! Decoding of raw cmux.protocol/2 stream items (`session.events`, `terminal.attach`) into the
//! bridge's [`model`](crate::model). Decoding is lenient: unknown fields and unknown item kinds
//! are skipped, so a newer cmux daemon keeps working with an older bridge. Traces store the raw
//! items, and both live and replayed items go through these functions.

use serde::Deserialize;
use serde_json::Value;

use crate::model::{
    Change, Entity, Layout, PaneDoc, RenderCursor, RenderItem, RenderRow, ResourceKind, ScreenDoc,
    SessionDoc, SessionItem, Size, TabDoc, TerminalDoc, WorkspaceDoc,
};

#[derive(Deserialize)]
struct RawRender {
    #[serde(default)]
    size: Option<Size>,
    cursor: RenderCursor,
    #[serde(default)]
    full_reset: bool,
    #[serde(default)]
    default_fg: Option<String>,
    #[serde(default)]
    default_bg: Option<String>,
    #[serde(default)]
    rows: Vec<RenderRow>,
}

#[derive(Deserialize)]
struct RawScroll {
    #[serde(deserialize_with = "decimal")]
    offset: u64,
    at_bottom: bool,
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum RawAttachItem {
    Snapshot {
        render: RawRender,
    },
    Patch {
        render: RawRender,
    },
    Scroll {
        scroll: RawScroll,
    },
    #[serde(other)]
    Other,
}

/// cmux sends 64-bit counters as decimal strings.
fn decimal<'de, D: serde::Deserializer<'de>>(d: D) -> Result<u64, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Num {
        S(String),
        N(u64),
    }
    match Num::deserialize(d)? {
        Num::S(s) => s.parse().map_err(serde::de::Error::custom),
        Num::N(n) => Ok(n),
    }
}

/// One `terminal.attach` item (the `item` of a `stream_item` envelope). `None` for kinds the
/// bridge does not use or cannot read.
pub fn decode_render(item: &Value) -> Option<RenderItem> {
    match RawAttachItem::deserialize(item).ok()? {
        RawAttachItem::Snapshot { render } => Some(RenderItem::Snapshot {
            size: render.size?,
            cursor: render.cursor,
            default_fg: render.default_fg?,
            default_bg: render.default_bg?,
            rows: render.rows,
        }),
        RawAttachItem::Patch { render } => Some(RenderItem::Patch {
            cursor: render.cursor,
            full_reset: render.full_reset,
            size: render.size,
            default_fg: render.default_fg,
            default_bg: render.default_bg,
            rows: render.rows,
        }),
        RawAttachItem::Scroll { scroll } => Some(RenderItem::Scroll {
            offset: scroll.offset,
            at_bottom: scroll.at_bottom,
        }),
        RawAttachItem::Other => None,
    }
}

#[derive(Deserialize)]
struct RawSession {
    #[serde(default)]
    name: Option<String>,
}

#[derive(Deserialize)]
struct RawLayoutDocument {
    root: Layout,
}

#[derive(Deserialize)]
struct RawScreen {
    id: String,
    workspace_id: String,
    #[serde(default)]
    name: Option<String>,
    index: u32,
    focused: bool,
    layout: RawLayoutDocument,
}

impl From<RawScreen> for ScreenDoc {
    fn from(s: RawScreen) -> ScreenDoc {
        ScreenDoc {
            id: s.id,
            workspace_id: s.workspace_id,
            name: s.name,
            index: s.index,
            focused: s.focused,
            layout: s.layout.root,
        }
    }
}

#[derive(Deserialize)]
struct RawTab {
    id: String,
    pane_id: String,
    #[serde(default)]
    name: Option<String>,
    index: u32,
    focused: bool,
    #[serde(default)]
    content_kind: Option<String>,
    #[serde(default)]
    content_id: Option<String>,
}

impl From<RawTab> for TabDoc {
    fn from(t: RawTab) -> TabDoc {
        let terminal_id = match t.content_kind.as_deref() {
            Some("terminal") | None => t.content_id.filter(|id| id.starts_with("term_")),
            _ => None,
        };
        TabDoc {
            id: t.id,
            pane_id: t.pane_id,
            name: t.name,
            index: t.index,
            focused: t.focused,
            terminal_id,
        }
    }
}

#[derive(Deserialize)]
struct RawResources {
    #[serde(default)]
    session: Option<RawSession>,
    #[serde(default)]
    workspaces: Vec<WorkspaceDoc>,
    #[serde(default)]
    screens: Vec<RawScreen>,
    #[serde(default)]
    panes: Vec<PaneDoc>,
    #[serde(default)]
    tabs: Vec<RawTab>,
    #[serde(default)]
    terminals: Vec<TerminalDoc>,
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum RawChange {
    Upsert {
        resource: String,
        value: Value,
    },
    Delete {
        resource: String,
        id: String,
    },
    #[serde(other)]
    Other,
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum RawEvent {
    Snapshot {
        snapshot: RawResources,
    },
    Delta {
        changes: Vec<RawChange>,
    },
    #[serde(other)]
    Other,
}

fn kind(resource: &str) -> Option<ResourceKind> {
    Some(match resource {
        "workspace" => ResourceKind::Workspace,
        "screen" => ResourceKind::Screen,
        "pane" => ResourceKind::Pane,
        "tab" => ResourceKind::Tab,
        "terminal" => ResourceKind::Terminal,
        _ => return None,
    })
}

fn entity(resource: ResourceKind, value: &Value) -> Option<Entity> {
    Some(match resource {
        ResourceKind::Workspace => Entity::Workspace(WorkspaceDoc::deserialize(value).ok()?),
        ResourceKind::Screen => Entity::Screen(RawScreen::deserialize(value).ok()?.into()),
        ResourceKind::Pane => Entity::Pane(PaneDoc::deserialize(value).ok()?),
        ResourceKind::Tab => Entity::Tab(RawTab::deserialize(value).ok()?.into()),
        ResourceKind::Terminal => Entity::Terminal(TerminalDoc::deserialize(value).ok()?),
    })
}

/// One `session.events` item. Deltas keep only the resources xmux mirrors; `None` when nothing
/// is left or the item cannot be read.
pub fn decode_session(item: &Value) -> Option<SessionItem> {
    match RawEvent::deserialize(item).ok()? {
        RawEvent::Snapshot { snapshot: s } => Some(SessionItem::Snapshot {
            session: SessionDoc {
                name: s.session.and_then(|s| s.name),
                workspaces: s.workspaces,
                screens: s.screens.into_iter().map(Into::into).collect(),
                panes: s.panes,
                tabs: s.tabs.into_iter().map(Into::into).collect(),
                terminals: s.terminals,
            },
        }),
        RawEvent::Delta { changes } => {
            let changes: Vec<Change> = changes
                .into_iter()
                .filter_map(|c| match c {
                    RawChange::Upsert { resource, value } => {
                        let value = entity(kind(&resource)?, &value)?;
                        Some(Change::Upsert { value })
                    }
                    RawChange::Delete { resource, id } => Some(Change::Delete {
                        resource: kind(&resource)?,
                        id,
                    }),
                    RawChange::Other => None,
                })
                .collect();
            (!changes.is_empty()).then_some(SessionItem::Delta { changes })
        }
        RawEvent::Other => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn decodes_a_snapshot_with_fields_this_bridge_does_not_know() {
        let item = json!({
            "kind": "snapshot",
            "terminal_id": "term_1",
            "render": {
                "cursor": {"blink": true, "color": null, "style": "block", "visible": true, "x": 0, "y": 1},
                "default_bg": "#000000", "default_fg": "#ffffff",
                "graphics": {"generation": 0, "images": [], "placements": []},
                "history_epoch": 2,
                "rows": [{"row": 0, "runs": [{"attrs": 1, "bg": null, "fg": null, "text": "hi"}]}],
                "scrollback_rows": 0,
                "size": {"cols": 80, "rows": 1}
            }
        });
        let Some(RenderItem::Snapshot {
            size,
            rows,
            default_fg,
            ..
        }) = decode_render(&item)
        else {
            panic!("not a snapshot");
        };
        assert_eq!(size, Size { cols: 80, rows: 1 });
        assert_eq!(default_fg, "#ffffff");
        assert_eq!(rows[0].runs[0].text, "hi");
        assert_eq!(rows[0].runs[0].fg, None);
    }

    #[test]
    fn unknown_kinds_are_skipped() {
        assert_eq!(decode_render(&json!({"kind": "hologram"})), None);
        assert_eq!(decode_session(&json!({"kind": "telepathy"})), None);
    }

    #[test]
    fn decodes_session_snapshots_and_deltas() {
        let snapshot = json!({
            "kind": "snapshot", "reset_reason": "initial",
            "snapshot": {
                "session": {"name": "main", "id": "session_1"},
                "machine": {"name": "local"},
                "workspaces": [{"id": "ws_1", "name": "w", "index": 0, "focused": true, "session_id": "s"}],
                "screens": [{"id": "screen_1", "workspace_id": "ws_1", "name": null, "index": 0, "focused": true,
                    "layout": {"version": 1, "active_pane_id": "pane_1", "zoomed_pane_id": null, "screen_id": "screen_1",
                        "root": {"kind": "leaf", "pane_id": "pane_1", "tab_ids": ["tab_1"], "active_tab_id": "tab_1"}}}],
                "panes": [{"id": "pane_1", "screen_id": "screen_1", "name": null, "focused": true, "zoomed": false}],
                "tabs": [{"id": "tab_1", "pane_id": "pane_1", "name": null, "index": 0, "focused": true,
                    "content_kind": "terminal", "content_id": "term_1", "extra": {}}],
                "terminals": [{"id": "term_1", "title": "", "cols": 80, "rows": 24, "running": true, "cwd": "/x",
                    "lifecycle": "running", "tab_ids": ["tab_1"]}],
                "clients": [], "browsers": []
            }
        });
        let Some(SessionItem::Snapshot { session }) = decode_session(&snapshot) else {
            panic!()
        };
        assert_eq!(session.name.as_deref(), Some("main"));
        assert_eq!(session.tabs[0].terminal_id.as_deref(), Some("term_1"));
        assert!(matches!(session.screens[0].layout, Layout::Leaf { .. }));

        let delta = json!({"kind": "delta", "previous_revision": "2", "revision": "3", "changes": [
            {"kind": "upsert", "resource": "workspace", "id": "ws_2", "sequence": 0,
             "value": {"id": "ws_2", "name": "two", "index": 1, "focused": false, "session_id": "s"}},
            {"kind": "upsert", "resource": "client", "id": "client_1", "sequence": 1, "value": {}},
            {"kind": "delete", "resource": "terminal", "id": "term_1", "sequence": 2}
        ]});
        let Some(SessionItem::Delta { changes }) = decode_session(&delta) else {
            panic!()
        };
        assert_eq!(changes.len(), 2, "client changes are not mirrored");
    }
}

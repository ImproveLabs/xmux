//! The cmux items the bridge consumes, as plain serde types with cmux.protocol/2 field names.
//! Live items come from the cmux SDK (`sdk`), recorded ones from trace files (`trace`); both feed
//! the same state machines, so replaying a trace exercises exactly the code that served it.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Size {
    pub cols: u16,
    pub rows: u16,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CursorStyle {
    #[default]
    Block,
    Underline,
    Bar,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RenderCursor {
    pub x: u16,
    pub y: u16,
    #[serde(default)]
    pub style: CursorStyle,
    #[serde(default)]
    pub blink: bool,
    #[serde(default)]
    pub visible: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UnderlineStyle {
    Single,
    Double,
    Curly,
    Dotted,
    Dashed,
}

/// cmux attribute bits in `RenderRun.attrs` (they differ from xmux's; see `terminal`).
pub mod cmux_attr {
    pub const BOLD: u32 = 0x0001;
    pub const ITALIC: u32 = 0x0002;
    pub const STRIKETHROUGH: u32 = 0x0004;
    pub const INVERSE: u32 = 0x0008;
    pub const FAINT: u32 = 0x0010;
    pub const INVISIBLE: u32 = 0x0020;
    pub const BLINK: u32 = 0x0040;
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RenderRun {
    pub text: String,
    /// Lowercase `#rrggbb`; absent means the terminal's default color.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fg: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bg: Option<String>,
    #[serde(default)]
    pub attrs: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub underline: Option<UnderlineStyle>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width_hint: Option<u16>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RenderRow {
    pub row: u16,
    pub runs: Vec<RenderRun>,
}

/// One item of a `terminal.attach` stream.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum RenderItem {
    /// The complete viewport: one row per terminal row.
    Snapshot {
        size: Size,
        cursor: RenderCursor,
        default_fg: String,
        default_bg: String,
        rows: Vec<RenderRow>,
    },
    /// Changed rows. With `full_reset` every row not listed is blank; a resize is always a reset.
    Patch {
        cursor: RenderCursor,
        #[serde(default)]
        full_reset: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        size: Option<Size>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        default_fg: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        default_bg: Option<String>,
        rows: Vec<RenderRow>,
    },
    /// Scrollback position of this viewer; the bridge shows the live viewport and ignores it.
    Scroll { offset: u64, at_bottom: bool },
}

/// Parses `#rrggbb` into opaque `0xFFRRGGBB`; anything else is `None`.
pub fn parse_color(hex: &str) -> Option<u32> {
    let digits = hex.strip_prefix('#')?;
    if digits.len() != 6 {
        return None;
    }
    u32::from_str_radix(digits, 16)
        .ok()
        .map(|rgb| 0xFF00_0000 | rgb)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SplitDirection {
    Horizontal,
    Vertical,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct LayoutColumn {
    pub width: f64,
    pub root: Box<Layout>,
}

/// A screen's layout tree, as in cmux's `LayoutDocument`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Layout {
    Leaf {
        pane_id: String,
        tab_ids: Vec<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        active_tab_id: Option<String>,
    },
    Split {
        direction: SplitDirection,
        ratio: f64,
        first: Box<Layout>,
        second: Box<Layout>,
    },
    Stack {
        pane_ids: Vec<String>,
        expanded_pane_id: String,
    },
    Viewport {
        columns: Vec<LayoutColumn>,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct WorkspaceDoc {
    pub id: String,
    pub name: String,
    pub index: u32,
    pub focused: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ScreenDoc {
    pub id: String,
    pub workspace_id: String,
    #[serde(default)]
    pub name: Option<String>,
    pub index: u32,
    pub focused: bool,
    pub layout: Layout,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PaneDoc {
    pub id: String,
    pub screen_id: String,
    #[serde(default)]
    pub name: Option<String>,
    pub focused: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct TabDoc {
    pub id: String,
    pub pane_id: String,
    #[serde(default)]
    pub name: Option<String>,
    pub index: u32,
    pub focused: bool,
    /// The terminal shown by this tab; `None` for browser tabs.
    #[serde(default)]
    pub terminal_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct TerminalDoc {
    pub id: String,
    pub title: String,
    pub cols: u16,
    pub rows: u16,
    pub running: bool,
}

/// The resources of one cmux session that xmux mirrors.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct SessionDoc {
    #[serde(default)]
    pub name: Option<String>,
    pub workspaces: Vec<WorkspaceDoc>,
    pub screens: Vec<ScreenDoc>,
    pub panes: Vec<PaneDoc>,
    pub tabs: Vec<TabDoc>,
    pub terminals: Vec<TerminalDoc>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "resource", rename_all = "snake_case")]
pub enum Entity {
    Workspace(WorkspaceDoc),
    Screen(ScreenDoc),
    Pane(PaneDoc),
    Tab(TabDoc),
    Terminal(TerminalDoc),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResourceKind {
    Workspace,
    Screen,
    Pane,
    Tab,
    Terminal,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Change {
    Upsert { value: Entity },
    Delete { resource: ResourceKind, id: String },
}

/// One item of a `session.events` stream.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SessionItem {
    Snapshot { session: SessionDoc },
    Delta { changes: Vec<Change> },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colors_parse_to_opaque_argb() {
        assert_eq!(parse_color("#1e1e1e"), Some(0xFF1E1E1E));
        assert_eq!(parse_color("#FFFFFF"), Some(0xFFFFFFFF));
        assert_eq!(parse_color("1e1e1e"), None);
        assert_eq!(parse_color("#1e1e"), None);
        assert_eq!(parse_color("#zzzzzz"), None);
    }

    #[test]
    fn render_items_round_trip_through_json_with_protocol_names() {
        let item = RenderItem::Patch {
            cursor: RenderCursor {
                x: 3,
                y: 1,
                style: CursorStyle::Bar,
                blink: true,
                visible: true,
            },
            full_reset: false,
            size: None,
            default_fg: None,
            default_bg: None,
            rows: vec![RenderRow {
                row: 1,
                runs: vec![RenderRun {
                    text: "hi".into(),
                    fg: Some("#ff0000".into()),
                    attrs: cmux_attr::BOLD,
                    ..Default::default()
                }],
            }],
        };
        let json = serde_json::to_string(&item).unwrap();
        assert!(
            json.starts_with(r#"{"kind":"patch","cursor":{"x":3"#),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<RenderItem>(&json).unwrap(), item);
    }
}

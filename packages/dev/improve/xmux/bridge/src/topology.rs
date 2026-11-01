//! The session's resources (workspaces, screens with layouts, panes, tabs, terminals) as the
//! bridge mirrors them from `session.events`, and their `Topology` frame.
//!
//! cmux ids are long strings; frames use small integers that stay stable for the life of the
//! bridge process ([`Ids`]), with the cmux id alongside for embedders that cross-reference.

use std::collections::HashMap;

use xmux_proto::frame::Payload;
use xmux_proto::{
    Frame, LayoutNode, NodeKind, Pane, Screen, SplitDirection as WireDirection, Tab, Terminal,
    Topology, Workspace,
};

use crate::model::{Change, Entity, Layout, ResourceKind, SessionDoc, SessionItem, SplitDirection};

/// cmux id <-> xmux id. cmux ids carry a kind prefix (`ws_`, `pane_`, ...), so one table serves
/// every kind.
#[derive(Debug, Default)]
pub struct Ids {
    by_cmux: HashMap<String, u32>,
    by_id: Vec<String>,
}

impl Ids {
    pub fn id(&mut self, cmux: &str) -> u32 {
        if let Some(&id) = self.by_cmux.get(cmux) {
            return id;
        }
        self.by_id.push(cmux.to_string());
        let id = self.by_id.len() as u32;
        self.by_cmux.insert(cmux.to_string(), id);
        id
    }

    pub fn get(&self, cmux: &str) -> Option<u32> {
        self.by_cmux.get(cmux).copied()
    }

    pub fn cmux(&self, id: u32) -> Option<&str> {
        self.by_id
            .get((id as usize).checked_sub(1)?)
            .map(String::as_str)
    }
}

/// Where a pane lives, for calls that need the full cmux path (`pane.focus_direction`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PanePath {
    pub workspace: String,
    pub screen: String,
    pub pane: String,
}

#[derive(Debug, Default)]
pub struct TopologyState {
    doc: SessionDoc,
    ids: Ids,
    revision: u32,
}

impl TopologyState {
    pub fn new() -> TopologyState {
        TopologyState::default()
    }

    pub fn doc(&self) -> &SessionDoc {
        &self.doc
    }

    pub fn ids(&mut self) -> &mut Ids {
        &mut self.ids
    }

    pub fn revision(&self) -> u32 {
        self.revision
    }

    /// Applies one `session.events` item; returns whether anything changed.
    pub fn apply(&mut self, item: &SessionItem) -> bool {
        let changed = match item {
            SessionItem::Snapshot { session } => {
                let changed = *session != self.doc;
                self.doc = session.clone();
                changed
            }
            SessionItem::Delta { changes } => {
                let mut changed = false;
                for change in changes {
                    changed |= self.apply_change(change);
                }
                changed
            }
        };
        if changed {
            self.revision = self.revision.wrapping_add(1);
        }
        changed
    }

    fn apply_change(&mut self, change: &Change) -> bool {
        fn upsert<T: PartialEq + Clone>(
            list: &mut Vec<T>,
            value: &T,
            id: impl Fn(&T) -> &str,
        ) -> bool {
            match list.iter_mut().find(|v| id(v) == id(value)) {
                Some(existing) if existing == value => false,
                Some(existing) => {
                    *existing = value.clone();
                    true
                }
                None => {
                    list.push(value.clone());
                    true
                }
            }
        }
        fn delete<T>(list: &mut Vec<T>, id: &str, key: impl Fn(&T) -> &str) -> bool {
            let before = list.len();
            list.retain(|v| key(v) != id);
            list.len() != before
        }
        let d = &mut self.doc;
        match change {
            Change::Upsert { value } => match value {
                Entity::Workspace(v) => upsert(&mut d.workspaces, v, |x| &x.id),
                Entity::Screen(v) => upsert(&mut d.screens, v, |x| &x.id),
                Entity::Pane(v) => upsert(&mut d.panes, v, |x| &x.id),
                Entity::Tab(v) => upsert(&mut d.tabs, v, |x| &x.id),
                Entity::Terminal(v) => upsert(&mut d.terminals, v, |x| &x.id),
            },
            Change::Delete { resource, id } => match resource {
                ResourceKind::Workspace => delete(&mut d.workspaces, id, |x| &x.id),
                ResourceKind::Screen => delete(&mut d.screens, id, |x| &x.id),
                ResourceKind::Pane => delete(&mut d.panes, id, |x| &x.id),
                ResourceKind::Tab => delete(&mut d.tabs, id, |x| &x.id),
                ResourceKind::Terminal => delete(&mut d.terminals, id, |x| &x.id),
            },
        }
    }

    /// cmux ids of every terminal that a tab shows.
    pub fn terminal_ids(&self) -> Vec<String> {
        self.doc.terminals.iter().map(|t| t.id.clone()).collect()
    }

    pub fn pane_path(&self, pane: &str) -> Option<PanePath> {
        let pane_doc = self.doc.panes.iter().find(|p| p.id == pane)?;
        let screen = self
            .doc
            .screens
            .iter()
            .find(|s| s.id == pane_doc.screen_id)?;
        Some(PanePath {
            workspace: screen.workspace_id.clone(),
            screen: screen.id.clone(),
            pane: pane.to_string(),
        })
    }

    /// The focused pane of a screen (by cmux id).
    pub fn focused_pane(&self, screen: &str) -> Option<PanePath> {
        let pane = self
            .doc
            .panes
            .iter()
            .find(|p| p.screen_id == screen && p.focused)?;
        self.pane_path(&pane.id)
    }

    pub fn frame(&mut self) -> Frame {
        Frame {
            payload: Some(Payload::Topology(self.topology())),
        }
    }

    pub fn topology(&mut self) -> Topology {
        let mut workspaces = self.doc.workspaces.clone();
        workspaces.sort_by_key(|w| w.index);
        let mut screens = self.doc.screens.clone();
        let ws_index: HashMap<&str, u32> = workspaces
            .iter()
            .map(|w| (w.id.as_str(), w.index))
            .collect();
        screens.sort_by_key(|s| {
            (
                ws_index
                    .get(s.workspace_id.as_str())
                    .copied()
                    .unwrap_or(u32::MAX),
                s.index,
            )
        });

        let focused_ws = workspaces.iter().find(|w| w.focused).map(|w| w.id.clone());
        let active_screen = screens
            .iter()
            .find(|s| s.focused && Some(&s.workspace_id) == focused_ws.as_ref())
            .or_else(|| screens.iter().find(|s| s.focused))
            .map(|s| s.id.clone());

        let wire_workspaces = workspaces
            .iter()
            .map(|w| Workspace {
                id: self.ids.id(&w.id),
                cmux_id: w.id.clone(),
                title: w.name.clone(),
            })
            .collect();
        let wire_screens = screens.iter().map(|s| self.screen(s)).collect();
        let terminals = self
            .doc
            .terminals
            .clone()
            .iter()
            .map(|t| Terminal {
                id: self.ids.id(&t.id),
                cmux_id: t.id.clone(),
                cols: u32::from(t.cols),
                rows: u32::from(t.rows),
                title: t.title.clone(),
                running: t.running,
            })
            .collect();
        Topology {
            revision: self.revision,
            workspaces: wire_workspaces,
            screens: wire_screens,
            terminals,
            active_screen: active_screen.map(|s| self.ids.id(&s)).unwrap_or(0),
        }
    }

    fn screen(&mut self, s: &crate::model::ScreenDoc) -> Screen {
        let mut leaves = Vec::new();
        collect_leaves(&s.layout, &mut leaves);
        let panes = leaves
            .iter()
            .map(|(pane, tab_ids, active)| self.pane(pane, tab_ids, active.as_deref()))
            .collect();
        let focused = self
            .doc
            .panes
            .iter()
            .find(|p| p.screen_id == s.id && p.focused)
            .map(|p| p.id.clone());
        Screen {
            id: self.ids.id(&s.id),
            cmux_id: s.id.clone(),
            title: s
                .name
                .clone()
                .unwrap_or_else(|| format!("Screen {}", s.index + 1)),
            workspace: self.ids.id(&s.workspace_id),
            layout: Some(self.layout(&s.layout)),
            panes,
            focused_pane: focused.map(|p| self.ids.id(&p)).unwrap_or(0),
        }
    }

    fn pane(&mut self, pane: &str, tab_ids: &[String], active: Option<&str>) -> Pane {
        let tabs: Vec<Tab> = tab_ids
            .iter()
            .filter_map(|tab_id| self.doc.tabs.iter().find(|t| &t.id == tab_id).cloned())
            .map(|tab| {
                let terminal = tab
                    .terminal_id
                    .as_deref()
                    .map(|t| self.ids.id(t))
                    .unwrap_or(0);
                let title = tab
                    .name
                    .clone()
                    .or_else(|| {
                        let term = tab.terminal_id.as_deref()?;
                        self.doc
                            .terminals
                            .iter()
                            .find(|t| t.id == term)
                            .map(|t| t.title.clone())
                    })
                    .unwrap_or_default();
                Tab {
                    id: self.ids.id(&tab.id),
                    cmux_id: tab.id.clone(),
                    title,
                    terminal,
                }
            })
            .collect();
        let active_tab = active
            .map(|a| self.ids.id(a))
            .or_else(|| tabs.first().map(|t| t.id))
            .unwrap_or(0);
        Pane {
            id: self.ids.id(pane),
            cmux_id: pane.to_string(),
            tabs,
            active_tab,
        }
    }

    fn layout(&mut self, layout: &Layout) -> LayoutNode {
        match layout {
            Layout::Leaf { pane_id, .. } => LayoutNode {
                kind: NodeKind::Leaf as i32,
                pane: self.ids.id(pane_id),
                ..Default::default()
            },
            Layout::Split {
                direction,
                ratio,
                first,
                second,
            } => LayoutNode {
                kind: NodeKind::Split as i32,
                direction: match direction {
                    SplitDirection::Horizontal => WireDirection::Horizontal,
                    SplitDirection::Vertical => WireDirection::Vertical,
                } as i32,
                ratio: *ratio as f32,
                children: vec![self.layout(first), self.layout(second)],
                ..Default::default()
            },
            Layout::Stack {
                pane_ids,
                expanded_pane_id,
            } => LayoutNode {
                kind: NodeKind::Stack as i32,
                children: pane_ids
                    .iter()
                    .map(|p| LayoutNode {
                        kind: NodeKind::Leaf as i32,
                        pane: self.ids.id(p),
                        ..Default::default()
                    })
                    .collect(),
                pane: self.ids.id(expanded_pane_id),
                ..Default::default()
            },
            Layout::Viewport { columns } => LayoutNode {
                kind: NodeKind::Viewport as i32,
                children: columns.iter().map(|c| self.layout(&c.root)).collect(),
                widths: columns.iter().map(|c| c.width as f32).collect(),
                ..Default::default()
            },
        }
    }
}

/// `(pane, tab ids, active tab)` for every pane in layout order. Panes inside a stack have no
/// tab list in the layout; their tabs come from the tab resources.
fn collect_leaves(layout: &Layout, out: &mut Vec<(String, Vec<String>, Option<String>)>) {
    match layout {
        Layout::Leaf {
            pane_id,
            tab_ids,
            active_tab_id,
        } => out.push((pane_id.clone(), tab_ids.clone(), active_tab_id.clone())),
        Layout::Split { first, second, .. } => {
            collect_leaves(first, out);
            collect_leaves(second, out);
        }
        Layout::Stack { pane_ids, .. } => {
            for p in pane_ids {
                out.push((p.clone(), Vec::new(), None));
            }
        }
        Layout::Viewport { columns } => {
            for c in columns {
                collect_leaves(&c.root, out);
            }
        }
    }
}

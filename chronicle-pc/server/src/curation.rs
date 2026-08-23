//! Curation op log (curation.py): append / replay (last-write-wins) / compact.

use std::io::Write;
use std::path::Path;

use serde_json::{json, Value};

use crate::errors::ChronicleError;
use crate::models::CurationOp;
use crate::paths::{atomic_write_text, read_json};

pub const OPS_REL: &str = "curation/ops/pc.jsonl";
const VALID_OPS: [&str; 12] = [
    "pin", "unpin", "hide", "unhide", "rename", "annotate", "set_doc",
    "merge", "link", "unlink", "create_concept", "delete_concept",
];

pub fn ops_path(root: &Path) -> std::path::PathBuf {
    root.join(OPS_REL)
}

/// Validate a curation op body (api/brain.py rules, exact messages).
pub fn validate_op(body: &CurationOp) -> Result<(), ChronicleError> {
    let k = body.op.as_str();
    if !VALID_OPS.contains(&k) {
        return Err(ChronicleError::msg(format!("unknown curation op: {k}")));
    }
    let req = |cond: bool, msg: &str| -> Result<(), ChronicleError> {
        if cond { Ok(()) } else { Err(ChronicleError::msg(msg.to_string())) }
    };
    match k {
        "pin" | "unpin" | "hide" | "unhide" | "delete_concept" => {
            req(body.node.is_some(), &format!("{k} requires node"))
        }
        "rename" => req(
            body.node.is_some() && body.label.is_some(),
            "rename requires node and label",
        ),
        "merge" => req(
            body.from_id.is_some() && body.into.is_some(),
            "merge requires from and into",
        ),
        "link" | "unlink" => req(
            body.from_id.is_some() && body.to.is_some(),
            &format!("{k} requires from and to"),
        ),
        "annotate" => req(
            body.node.is_some() && body.text.is_some(),
            "annotate requires node and text",
        ),
        "create_concept" => req(
            body.id.is_some() && body.label.is_some(),
            "create_concept requires id and label",
        ),
        "set_doc" => req(
            body.node.is_some() && body.doc.is_some(),
            "set_doc requires node and doc",
        ),
        _ => Ok(()),
    }
}

/// append_op — plain line append (append-only contract; no lock in python).
pub fn append_op(root: &Path, mut op: CurationOp) -> Result<Value, ChronicleError> {
    validate_op(&op)?;
    if op.ts.is_empty() {
        op.ts = crate::timeutil::now_iso();
    }
    op.device = "pc".into();
    let path = ops_path(root);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // exclude_none dump by alias.
    let mut value = serde_json::to_value(&op).map_err(|e| ChronicleError::msg(e.to_string()))?;
    value.as_object_mut().unwrap().retain(|_, v| !v.is_null());
    let line = serde_json::to_string(&value).map_err(|e| ChronicleError::msg(e.to_string()))?;
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&path)?;
    writeln!(f, "{line}")?;
    Ok(json!({"ok": true, "op": value, "path": OPS_REL}))
}

pub fn read_ops(root: &Path) -> Vec<CurationOp> {
    let raw = std::fs::read_to_string(ops_path(root)).unwrap_or_default();
    raw.lines()
        .filter_map(|l| {
            let l = l.trim();
            if l.is_empty() {
                return None;
            }
            serde_json::from_str::<Value>(l).ok().and_then(|v| {
                serde_json::from_value::<CurationOp>(v).ok()
            })
        })
        .collect()
}

/// apply_ops_to_graph — last-write-wins node state + edge states + merges.
/// Operates on a mutable serde graph value (nodes/edges arrays of objects).
pub fn apply_ops_to_graph(graph: &mut Value, ops: &[CurationOp]) -> usize {
    if !graph.is_object() {
        *graph = json!({});
    }
    if graph.get("nodes").and_then(Value::as_array).is_none() {
        graph["nodes"] = json!([]);
    }
    if graph.get("edges").and_then(Value::as_array).is_none() {
        graph["edges"] = json!([]);
    }

    fn find_node<'a>(nodes: &'a mut Value, nid: &str) -> Option<&'a mut Value> {
        nodes
            .as_array_mut()?
            .iter_mut()
            .find(|n| n.get("id").and_then(Value::as_str) == Some(nid))
    }
    fn edges_mut(graph: &mut Value) -> &mut Vec<Value> {
        graph.get_mut("edges").unwrap().as_array_mut().unwrap()
    }

    let mut applied = 0usize;
    for op in ops {
        match op.op.as_str() {
            "pin" | "unpin" | "hide" | "unhide" => {
                if let Some(nid) = &op.node {
                    if let Some(n) = find_node(graph.get_mut("nodes").unwrap(), nid) {
                        let flag = matches!(op.op.as_str(), "pin" | "hide");
                        let key = if matches!(op.op.as_str(), "pin" | "unpin") { "pinned" } else { "hidden" };
                        n[key] = json!(flag);
                        applied += 1;
                    }
                }
            }
            "rename" => {
                if let (Some(nid), Some(label)) = (&op.node, &op.label) {
                    if let Some(n) = find_node(graph.get_mut("nodes").unwrap(), nid) {
                        n["label"] = json!(label);
                        applied += 1;
                    }
                }
            }
            "annotate" => {
                if let (Some(nid), Some(text)) = (&op.node, &op.text) {
                    if let Some(n) = find_node(graph.get_mut("nodes").unwrap(), nid) {
                        n["annotation"] = json!(text);
                        applied += 1;
                    }
                }
            }
            "set_doc" => {
                if let (Some(nid), Some(doc)) = (&op.node, &op.doc) {
                    if let Some(n) = find_node(graph.get_mut("nodes").unwrap(), nid) {
                        n["doc"] = json!(doc);
                        applied += 1;
                    }
                }
            }
            "create_concept" => {
                if let (Some(cid), Some(label)) = (&op.id, &op.label) {
                    if find_node(graph.get_mut("nodes").unwrap(), cid).is_none() {
                        let kind = op
                            .extra
                            .get("kind")
                            .and_then(Value::as_str)
                            .unwrap_or("concept");
                        graph["nodes"]
                            .as_array_mut()
                            .unwrap()
                            .push(json!({"id": cid, "kind": kind, "label": label}));
                    }
                    applied += 1;
                }
            }
            "delete_concept" => {
                if let Some(cid) = &op.node {
                    if let Some(arr) = graph.get_mut("nodes").unwrap().as_array_mut() {
                        arr.retain(|n| n.get("id").and_then(Value::as_str) != Some(cid.as_str()));
                    }
                    if let Some(arr) = graph.get_mut("edges").unwrap().as_array_mut() {
                        arr.retain(|e| {
                            let f = e.get("from").and_then(Value::as_str).unwrap_or_default();
                            let t = e.get("to").and_then(Value::as_str).unwrap_or_default();
                            f != *cid && t != *cid
                        });
                    }
                    applied += 1;
                }
            }
            "merge" => {
                if let (Some(from_id), Some(into)) = (&op.from_id, &op.into) {
                    if let Some(arr) = graph.get_mut("nodes").unwrap().as_array_mut() {
                        if let Some(pos) = arr
                            .iter()
                            .position(|n| n.get("id").and_then(Value::as_str) == Some(from_id.as_str()))
                        {
                            let src = arr.remove(pos);
                            if let Some(dst) = arr.iter_mut().find(|n| {
                                n.get("id").and_then(Value::as_str) == Some(into.as_str())
                            }) {
                                if let (Some(w), Some(dw)) = (
                                    src.get("weight").and_then(Value::as_f64),
                                    dst.get("weight").and_then(Value::as_f64),
                                ) {
                                    dst["weight"] = json!(dw + w);
                                }
                                if dst.get("label").and_then(Value::as_str).map(str::is_empty).unwrap_or(true) {
                                    if let Some(l) = src.get("label") {
                                        dst["label"] = l.clone();
                                    }
                                }
                            }
                        }
                    }
                    {
                        let arr = edges_mut(graph);
                        for e in arr.iter_mut() {
                            let f = e.get("from").and_then(Value::as_str).unwrap_or_default();
                            let t = e.get("to").and_then(Value::as_str).unwrap_or_default();
                            let mut nf = f.to_string();
                            let mut nt = t.to_string();
                            if f == *from_id { nf = into.clone(); }
                            if t == *from_id { nt = into.clone(); }
                            e["from"] = json!(nf);
                            e["to"] = json!(nt);
                        }
                        arr.retain(|e| {
                            e.get("from").and_then(Value::as_str) != e.get("to").and_then(Value::as_str)
                                || e.get("from").is_none()
                        });
                    }
                    applied += 1;
                }
            }
            "link" | "unlink" => {
                if let (Some(from_id), Some(to)) = (&op.from_id, &op.to) {
                    let rel = op.rel.clone().unwrap_or_else(|| "manual".into());
                    let arr = edges_mut(graph);
                    let existing_pos = arr.iter().position(|e| {
                        e.get("from").and_then(Value::as_str) == Some(from_id.as_str())
                            && e.get("to").and_then(Value::as_str) == Some(to.as_str())
                            && e.get("rel")
                                .and_then(Value::as_str)
                                .unwrap_or("manual")
                                == rel
                    });
                    if op.op == "unlink" {
                        if let Some(p) = existing_pos {
                            arr.remove(p);
                        }
                    } else if existing_pos.is_none() {
                        let mut edge = json!({"from": from_id, "to": to, "rel": rel});
                        if let Some(s) = op.extra.get("score") {
                            edge["score"] = s.clone();
                        }
                        arr.push(edge);
                    }
                    applied += 1;
                }
            }
            _ => {}
        }
    }
    applied
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_op(op: &str, extra: Value) -> CurationOp {
        let mut v = json!({"op": op, "ts": "2026-01-01T00:00:00+00:00", "device": "pc"});
        v.as_object_mut().unwrap().extend(extra.as_object().cloned().unwrap_or_default());
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn validates_required_fields_with_exact_messages() {
        assert_eq!(
            validate_op(&sample_op("pin", json!({}))).unwrap_err().to_string(),
            "pin requires node"
        );
        assert_eq!(
            validate_op(&sample_op("bogus", json!({}))).unwrap_err().to_string(),
            "unknown curation op: bogus"
        );
        assert!(validate_op(&sample_op("pin", json!({"node": "topic:x"}))).is_ok());
    }

    #[test]
    fn append_and_replay_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        append_op(root, sample_op("pin", json!({"node": "topic:a"}))).unwrap();
        append_op(root, sample_op("unpin", json!({"node": "topic:a"}))).unwrap();
        let ops = read_ops(root);
        assert_eq!(ops.len(), 2);
        let mut graph = json!({
            "version": 1, "generated": "g",
            "nodes": [{"id": "topic:a", "kind": "topic", "label": "A", "pinned": true}],
            "edges": [],
        });
        let applied = apply_ops_to_graph(&mut graph, &ops);
        assert_eq!(applied, 2);
        assert_eq!(graph["nodes"][0]["pinned"], json!(false));
        // device forced pc + ts defaulted already set here.
        let raw = std::fs::read_to_string(ops_path(root)).unwrap();
        assert!(raw.trim_end().ends_with('}'));
    }

    #[test]
    fn merge_moves_edges_and_weights() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        append_op(root, sample_op("merge", json!({"from": "concept:a", "into": "concept:b"}))).unwrap();
        let ops = read_ops(root);
        let mut graph = json!({
            "version": 1, "generated": "g",
            "nodes": [
                {"id": "concept:a", "kind": "concept", "label": "A", "weight": 2.0},
                {"id": "concept:b", "kind": "concept", "label": "B", "weight": 1.0},
            ],
            "edges": [
                {"from": "entry:x", "to": "concept:a", "rel": "mentions"},
                {"from": "concept:a", "to": "entry:y", "rel": "about"},
                {"from": "entry:z", "to": "concept:b", "rel": "mentions"},
            ],
        });
        apply_ops_to_graph(&mut graph, &ops);
        let ids: Vec<&str> = graph["nodes"].as_array().unwrap().iter().map(|n| n["id"].as_str().unwrap()).collect();
        assert_eq!(ids, vec!["concept:b"]);
        assert_eq!(graph["nodes"][0]["weight"], json!(3.0));
        let tos: Vec<&str> = graph["edges"].as_array().unwrap().iter().map(|e| e["to"].as_str().unwrap()).collect();
        assert!(tos.contains(&"concept:b"));
    }
}

// ---------------------------------------------------------------------------
// Compaction (curation.py replay-collapse) — byte-order faithful emission
// ---------------------------------------------------------------------------

#[derive(Default)]
struct NodeState {
    pinned: Option<bool>,
    hidden: Option<bool>,
    label: Option<String>,
    text: Option<String>,
    doc: Option<String>,
    ts: String,
    device: String,
}

/// compact_ops: last-write-wins collapse of the pc.jsonl op log.
/// Returns stats; writes atomically unless dry_run.
pub fn compact_ops(root: &Path, dry_run: bool) -> Result<Value, ChronicleError> {
    let ops = read_ops(root);
    let before = ops.len();

    let mut node_state: std::collections::BTreeMap<String, NodeState> = Default::default();
    let mut creates: std::collections::BTreeMap<String, CurationOp> = Default::default();
    let mut deletes: Vec<CurationOp> = Vec::new();
    let mut merges: Vec<CurationOp> = Vec::new();
    let mut edge_state: std::collections::BTreeMap<(String, String, String), Option<CurationOp>> =
        Default::default();

    let touch = |st: &mut NodeState, op: &CurationOp| {
        st.ts = op.ts.clone();
        st.device = op.device.clone();
    };

    for op in &ops {
        let k = op.op.as_str();
        match k {
            "pin" | "unpin" => {
                if let Some(nid) = &op.node {
                    let st = node_state.entry(nid.clone()).or_default();
                    st.pinned = Some(k == "pin");
                    touch(st, op);
                }
            }
            "hide" | "unhide" => {
                if let Some(nid) = &op.node {
                    let st = node_state.entry(nid.clone()).or_default();
                    st.hidden = Some(k == "hide");
                    touch(st, op);
                }
            }
            "rename" => {
                if let Some(nid) = &op.node {
                    let st = node_state.entry(nid.clone()).or_default();
                    st.label = op.label.clone();
                    touch(st, op);
                }
            }
            "annotate" => {
                if let Some(nid) = &op.node {
                    let st = node_state.entry(nid.clone()).or_default();
                    st.text = op.text.clone();
                    touch(st, op);
                }
            }
            "set_doc" => {
                if let Some(nid) = &op.node {
                    let st = node_state.entry(nid.clone()).or_default();
                    st.doc = op.doc.clone();
                    touch(st, op);
                }
            }
            "create_concept" => {
                if let Some(cid) = &op.id {
                    creates.insert(cid.clone(), op.clone());
                    // Cancel any pending delete for this id.
                    deletes.retain(|d| d.node.as_deref() != Some(cid.as_str()));
                }
            }
            "delete_concept" => {
                if let Some(cid) = &op.node {
                    if creates.remove(cid).is_some() {
                        // Create+delete cancel out entirely.
                        node_state.remove(cid);
                    } else {
                        deletes.push(op.clone());
                        node_state.remove(cid);
                    }
                }
            }
            "merge" => merges.push(op.clone()),
            "link" | "unlink" => {
                if let (Some(f), Some(t)) = (&op.from_id, &op.to) {
                    let rel = op.rel.clone().unwrap_or_else(|| "manual".into());
                    edge_state.insert((f.clone(), t.clone(), rel), Some(op.clone()).filter(|_| k == "link"));
                }
            }
            _ => {}
        }
    }

    let synth_base = |op: &str, nid: &str| -> Value {
        json!({"op": op, "node": nid})
    };

    let mut compacted: Vec<Value> = Vec::new();

    // 1. Creates sorted by id, skipping ids present in deletes.
    let deleted_ids: std::collections::HashSet<&String> =
        deletes.iter().filter_map(|d| d.node.as_ref()).collect();
    for (cid, op) in creates.iter() {
        if deleted_ids.contains(cid) || cid.is_empty() {
            continue;
        }
        let mut v = to_wire(op);
        v["device"] = json!("pc");
        compacted.push(v);
    }
    // 2. Deletes sorted by (ts, node).
    let mut sorted_deletes = deletes;
    sorted_deletes.sort_by(|a, b| {
        (a.ts.clone(), a.node.clone().unwrap_or_default())
            .cmp(&(b.ts.clone(), b.node.clone().unwrap_or_default()))
    });
    for d in sorted_deletes {
        let mut v = to_wire(&d);
        v["device"] = json!("pc");
        compacted.push(v);
    }
    // 3. Merges in encounter order.
    for m in &merges {
        compacted.push(to_wire(m));
    }
    // 4. Per surviving node, fixed sub-order.
    for (nid, st) in &node_state {
        let emit = |op_name: &str, extra: Value, compacted: &mut Vec<Value>| {
            let mut base: Value = synth_base(op_name, nid);
            let obj = base.as_object_mut().unwrap();
            if st.ts.is_empty() {
                obj.insert("ts".into(), json!(""));
            }
            if st.device.is_empty() {
                obj.insert("device".into(), json!("pc"));
            }
            if let Value::Object(extra_map) = extra {
                for (k2, v2) in extra_map {
                    obj.insert(k2, v2);
                }
            }
            compacted.push(base);
        };
        if let Some(pinned) = st.pinned {
            emit(if pinned { "pin" } else { "unpin" }, json!({}), &mut compacted);
        }
        if let Some(hidden) = st.hidden {
            emit(if hidden { "hide" } else { "unhide" }, json!({}), &mut compacted);
        }
        if let Some(label) = &st.label {
            emit("rename", json!({"label": label}), &mut compacted);
        }
        if let Some(text) = &st.text {
            emit("annotate", json!({"text": text}), &mut compacted);
        }
        if st.doc.as_deref().is_some_and(|d| !d.is_empty()) {
            emit("set_doc", json!({"doc": st.doc.clone()}), &mut compacted);
        }
    }
    // 5. Edge states sorted by (from,to,rel); None → synthesized unlink.
    for ((f, t, rel), op_opt) in edge_state {
        match op_opt {
            None => {
                let mut u = synth_base("unlink", "");
                let obj = u.as_object_mut().unwrap();
                obj.remove("node");
                obj.insert("from".into(), json!(f));
                obj.insert("to".into(), json!(t));
                obj.insert("rel".into(), json!(rel));
                obj.insert("ts".into(), json!(""));
                obj.insert("device".into(), json!("pc"));
                compacted.push(u);
            }
            Some(op) => compacted.push(to_wire(&op)),
        }
    }

    let after = compacted.len();
    if !dry_run {
        let mut body = String::new();
        for op in &compacted {
            body.push_str(&serde_json::to_string(op).map_err(|e| ChronicleError::msg(e.to_string()))?);
            body.push('\n');
        }
        crate::paths::atomic_write_text(&ops_path(root), &body)?;
    }
    Ok(json!({
        "ok": true,
        "before": before,
        "after": after,
        "dry_run": dry_run,
        "path": OPS_REL,
    }))
}

/// exclude_none dump by alias (wire form shared with append_op).
fn to_wire(op: &CurationOp) -> Value {
    let mut v = serde_json::to_value(op).unwrap_or(Value::Null);
    if let Some(obj) = v.as_object_mut() {
        obj.retain(|_, val| !val.is_null());
    }
    v
}

#[cfg(test)]
mod compact_tests {
    use super::*;
    use serde_json::json;

    fn op(v: Value) -> CurationOp {
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn collapses_pin_unpin_and_cancels_create_delete() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let ops = vec![
            op(json!({"op":"pin","node":"topic:a"})),
            op(json!({"op":"unpin","node":"topic:a"})),
            op(json!({"op":"create_concept","id":"concept:x","label":"X"})),
            op(json!({"op":"delete_concept","node":"concept:x"})),
            op(json!({"op":"rename","node":"topic:b","label":"Bee"})),
        ];
        for o in ops {
            append_op(root, o).unwrap();
        }
        let stats = compact_ops(root, false).unwrap();
        assert_eq!(stats["before"], json!(5));
        let raw = std::fs::read_to_string(ops_path(root)).unwrap();
        let lines: Vec<&str> = raw.lines().collect();
        // pin+unpin collapse to one unpin; create+delete vanish; rename survives.
        assert_eq!(lines.len(), 2, "{raw}");
        assert!(lines[0].contains("\"unpin\"") || lines[1].contains("\"unpin\""));
        assert!(raw.contains("rename"));
        assert_eq!(stats["after"], json!(2));
    }

    #[test]
    fn unlink_synthesis_and_merge_preserved() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        append_op(root, op(json!({"op":"link","from":"entry:a","to":"topic:t","rel":"about"}))).unwrap();
        append_op(root, op(json!({"op":"merge","from":"topic:p","into":"topic:q"}))).unwrap();
        append_op(root, op(json!({"op":"unlink","from":"entry:a","to":"topic:t","rel":"about"}))).unwrap();
        compact_ops(root, false).unwrap();
        let raw = std::fs::read_to_string(ops_path(root)).unwrap();
        let lines: Vec<serde_json::Value> =
            raw.lines().map(|l| serde_json::from_str(l).unwrap()).collect();
        // link+unlink collapse to synthesized unlink; merge kept verbatim.
        assert_eq!(lines.len(), 2, "{lines:?}");
        assert_eq!(lines[0]["op"], json!("merge"));
        assert_eq!(lines[1]["op"], json!("unlink"));
        assert_eq!(lines[1]["ts"], json!(""));
        assert_eq!(lines[1]["device"], json!("pc"));
        assert_eq!(lines[1]["rel"], json!("about"));
    }

    #[test]
    fn dry_run_counts_without_writing() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        append_op(root, op(json!({"op":"pin","node":"topic:a"}))).unwrap();
        append_op(root, op(json!({"op":"pin","node":"topic:a"}))).unwrap();
        let before_raw = std::fs::read_to_string(ops_path(root)).unwrap();
        let stats = compact_ops(root, true).unwrap();
        assert_eq!(stats["after"], json!(1));
        assert_eq!(
            std::fs::read_to_string(ops_path(root)).unwrap(),
            before_raw,
            "dry-run must not rewrite"
        );
    }
}

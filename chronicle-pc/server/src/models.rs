//! Serde mirrors of the pydantic contract models (extra="allow" semantics via
//! flattened ordered maps; alias renames preserved).

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub const ENTRY_TYPES: [&str; 4] = ["log", "idea", "dream", "reflection"];

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Entry {
    #[serde(default = "one")]
    pub version: i64,
    pub id: String,
    #[serde(default)]
    pub ts: String,
    #[serde(rename = "type", default = "def_type")]
    pub kind: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub images: Vec<String>,
    #[serde(default)]
    pub audio: Vec<String>,
    #[serde(default)]
    pub mood: Option<i64>,
    #[serde(default)]
    pub processed: bool,
    #[serde(default)]
    pub filed: bool,
    #[serde(default)]
    pub filed_content_hash: Option<String>,
    #[serde(default)]
    pub filed_path: Option<String>,
    #[serde(default)]
    pub prose_edited: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

fn one() -> i64 { 1 }
fn def_type() -> String { "log".into() }

impl Entry {
    /// Pydantic rejects unknown `type` values on load; mirror that.
    pub fn valid_type(t: &str) -> bool {
        ENTRY_TYPES.contains(&t)
    }

    fn extra_get(&self, key: &str) -> Option<&Value> {
        self.extra.get(key)
    }

    /// journal.get_filed chain: typed field if truthy else extras.
    pub fn get_filed(&self) -> bool {
        self.filed || self.extra_get("filed").and_then(Value::as_bool).unwrap_or(false)
    }

    pub fn get_filed_hash(&self) -> Option<String> {
        self.filed_content_hash
            .clone()
            .filter(|h| !h.is_empty())
            .or_else(|| {
                self.extra_get("filed_content_hash")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
            })
    }

    pub fn get_filed_path(&self) -> Option<String> {
        self.filed_path
            .clone()
            .filter(|p| !p.is_empty())
            .or_else(|| {
                self.extra_get("filed_path")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
            })
    }

    pub fn get_prose_edited(&self) -> bool {
        self.prose_edited
            || self.extra_get("prose_edited").and_then(Value::as_bool).unwrap_or(false)
    }

    /// `_entry_dict`: full dump keeping nulls, popping empty audio.
    pub fn to_api_value(&self) -> Value {
        let mut v = serde_json::to_value(self).expect("entry serializes");
        let obj = v.as_object_mut().unwrap();
        if obj.get("audio").and_then(Value::as_array).is_none_or(|a| a.is_empty()) {
            obj.remove("audio");
        }
        v
    }

    /// save_entry disk form: strip falsy audio + filed trio when unfilled.
    pub fn to_disk_value(&self) -> Value {
        let mut v = self.to_api_value();
        let obj = v.as_object_mut().unwrap();
        if !self.get_filed() {
            obj.remove("filed");
            obj.remove("filed_content_hash");
            obj.remove("filed_path");
        }
        v
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphNode {
    pub id: String,
    pub kind: String,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub weight: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pinned: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hidden: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub annotation: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub doc: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entry_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ts: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphEdge {
    #[serde(rename = "from")]
    pub from_id: String,
    pub to: String,
    pub rel: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub score: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Enrichment {
    #[serde(default)]
    pub auto_tags: Vec<String>,
    #[serde(default)]
    pub summary_line: String,
    #[serde(default)]
    pub entities: Vec<Value>,
}

/// Curation op wire shape (`from` is a JSON key; device forced pc upstream).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CurationOp {
    pub op: String,
    #[serde(default)]
    pub ts: String,
    #[serde(default)]
    pub device: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub node: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(rename = "from", default, skip_serializing_if = "Option::is_none")]
    pub from_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub into: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub to: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rel: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub doc: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

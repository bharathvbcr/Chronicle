//! Error taxonomy mirroring the Python side's exception → HTTP mapping.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::{json, Value};

#[derive(Debug, thiserror::Error)]
pub enum ChronicleError {
    #[error("{0}")]
    Layout(String),
    #[error("entry not found: {0}")]
    EntryNotFound(String),
    #[error("failed to load entry: {0}")]
    EntryLoad(String),
    #[error("invalid entry id: {0}")]
    InvalidEntryId(String),
    #[error("{0}")]
    MediaPath(String),
    #[error("journal fence hash mismatch")]
    JournalAmendConflict {
        on_disk_hash: Option<String>,
        filed_content_hash: Option<String>,
    },
    #[error("{0}")]
    JournalAmendNotFound(String),
    #[error("{0}")]
    Llm(String),
    #[error("Ollama unreachable: {0}")]
    OllamaUnreachable(String),
    #[error("{0}")]
    Io(String),
    #[error("{0}")]
    Other(String),
}

impl ChronicleError {
    pub fn msg(m: impl Into<String>) -> Self {
        Self::Other(m.into())
    }
    pub fn io(e: std::io::Error) -> Self {
        Self::Io(e.to_string())
    }
}

impl From<std::io::Error> for ChronicleError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e.to_string())
    }
}

/// API-level error carrying an exact status + body (FastAPI HTTPException
/// semantics: body is `{"detail": <value>}` where value may be a string or a
/// nested object — the nested 409 shapes are contract-relevant).
#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub detail: Value,
}

impl ApiError {
    pub fn new(status: StatusCode, detail: impl Into<Value>) -> Self {
        Self { status, detail: detail.into() }
    }
    pub fn bad_request(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, json!(msg.into()))
    }
    pub fn not_found(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, json!(msg.into()))
    }
    pub fn internal(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, json!(msg.into()))
    }
    /// FastAPI nests dict details: `{"detail": {"detail": ..., ...}}`.
    pub fn conflict_object(obj: Value) -> Self {
        Self::new(StatusCode::CONFLICT, obj)
    }
    pub fn conflict(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::CONFLICT, json!(msg.into()))
    }
    pub fn unauthorized() -> Self {
        Self::new(
            StatusCode::UNAUTHORIZED,
            json!({"ok": false, "error": "missing or invalid X-Chronicle-Token"}),
        )
    }
    pub fn too_many(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::TOO_MANY_REQUESTS, json!(msg.into()))
    }
    pub fn payload_too_large(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, json!(msg.into()))
    }
}

impl From<ChronicleError> for ApiError {
    fn from(e: ChronicleError) -> Self {
        match e {
            ChronicleError::Layout(m) => ApiError::conflict(m),
            ChronicleError::EntryNotFound(id) => ApiError::not_found(format!("entry not found: {id}")),
            ChronicleError::EntryLoad(id) => ApiError::internal(format!("failed to load entry: {id}")),
            ChronicleError::InvalidEntryId(id) => ApiError::bad_request(format!("invalid entry id: {id}")),
            ChronicleError::MediaPath(m) => ApiError::bad_request(m),
            ChronicleError::JournalAmendConflict { on_disk_hash, filed_content_hash } => ApiError::conflict_object(json!({
                "detail": "journal fence hash mismatch",
                "on_disk_hash": on_disk_hash,
                "filed_content_hash": filed_content_hash,
            })),
            ChronicleError::JournalAmendNotFound(m) => ApiError::not_found(m),
            ChronicleError::Llm(m) | ChronicleError::OllamaUnreachable(m) | ChronicleError::Io(m) | ChronicleError::Other(m) => {
                ApiError::internal(m)
            }
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "detail": self.detail }))).into_response()
    }
}

pub type ApiResult<T> = Result<T, ApiError>;

impl From<crate::lock::LockError> for ApiError {
    fn from(e: crate::lock::LockError) -> Self {
        match e {
            crate::lock::LockError::Busy => ApiError::conflict("vault is busy: another operation holds the lock"),
            crate::lock::LockError::Timeout => ApiError::new(
                axum::http::StatusCode::SERVICE_UNAVAILABLE,
                json!("vault is busy processing; retry shortly"),
            ),
            other => ApiError::internal(other.to_string()),
        }
    }
}

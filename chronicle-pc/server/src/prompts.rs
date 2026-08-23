//! Agent prompt templates, embedded at build time (parity with agents/*.md).

pub fn load_agent(name: &str) -> &'static str {
    match name {
        "ask" => include_str!("../assets/agents/ask.md"),
        "resume" => include_str!("../assets/agents/resume.md"),
        "recall" => include_str!("../assets/agents/recall.md"),
        "brain_extract" => include_str!("../assets/agents/brain_extract.md"),
        "brain_link" => include_str!("../assets/agents/brain_link.md"),
        _ => "",
    }
    .trim()
}

pub const KB_ENRICH_PROMPT: &str = r#"You enrich a knowledge-base note for resume retrieval.
You are a reasoning model: think privately if needed, then emit ONLY final JSON
(never include <think> tags or chain-of-thought).

Return JSON only with this shape:
{
  "summary": "1-3 sentence factual summary",
  "skills": ["skill or tech names"],
  "highlights": ["STAR-style metric bullet", "..."]
}
Highlights should be concrete, measurable bullets (action + result + metric when possible).
If the note has little resume value, still return a short summary and empty arrays."#;

pub const RECALL_FALLBACK: &str =
    "You are Chronicle Recall, a private local assistant. Answer using only the provided {SCOPE}. Cite ids in square brackets. If unsure, say so.";

"""Pydantic models aligned with contract/*.schema.json."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class Entry(BaseModel):
    """Entry JSON; unknown keys are preserved on load→save (soft-roll new fields)."""

    model_config = ConfigDict(extra="allow")

    version: int = 1
    id: str
    ts: str
    type: Literal["log", "idea", "dream", "reflection"]
    text: str = ""
    tags: list[str] = Field(default_factory=list)
    images: list[str] = Field(default_factory=list)
    audio: list[str] = Field(default_factory=list)
    mood: int | None = None
    processed: bool = False
    # Phase 4 file-once (optional; absent on pre-cutover entries)
    filed: bool = False
    filed_content_hash: str | None = None
    filed_path: str | None = None
    # v1.9: user amended the fence body via serve; pipeline must not re-render it
    prose_edited: bool = False


class ConfigModels(BaseModel):
    model_config = ConfigDict(extra="allow")

    llm: str = "maxwell1500/ornith-35b:Q4_K_M"
    embed: str = "nomic-embed-text"
    vision: str = "llama3.2-vision:11b"
    whisper: str = "whisper"


class OllamaOptions(BaseModel):
    model_config = ConfigDict(extra="allow")

    base_url: str = "http://localhost:11434"
    num_ctx: int = 32768
    temperature: float | None = None


class GrokOptions(BaseModel):
    """Non-secret Grok settings (API key lives in secrets.json / env)."""

    model_config = ConfigDict(extra="allow")

    base_url: str = "https://api.x.ai/v1"
    model: str | None = None


class VertexOptions(BaseModel):
    """Non-secret Vertex settings (ADC / SA JSON stay off-vault)."""

    model_config = ConfigDict(extra="allow")

    project: str | None = None
    location: str = "us-central1"
    model: str | None = None


class LlmOptions(BaseModel):
    """Active chat/vision provider + consent (secrets never in this file)."""

    model_config = ConfigDict(extra="allow")

    provider: Literal["ollama", "grok", "vertex"] = "ollama"
    cloud_consent: bool = False
    vision_cloud_consent: bool = False
    grok: GrokOptions = Field(default_factory=GrokOptions)
    vertex: VertexOptions = Field(default_factory=VertexOptions)


class E2eeKdf(BaseModel):
    """Key-derivation params (non-secret) shared by phone + PC."""

    model_config = ConfigDict(extra="allow")

    alg: Literal["pbkdf2-sha256"] = "pbkdf2-sha256"
    iter: int = 600_000
    salt: str = ""


class E2eeCheck(BaseModel):
    """AES-GCM check blob verifying a passphrase without storing it."""

    model_config = ConfigDict(extra="allow")

    nonce: str = ""
    ct: str = ""


class E2eeOptions(BaseModel):
    """Opt-in field-level encryption for entry text (see e2ee.py)."""

    model_config = ConfigDict(extra="allow")

    enabled: bool = False
    kdf: E2eeKdf = Field(default_factory=E2eeKdf)
    check: E2eeCheck = Field(default_factory=E2eeCheck)


class ChronicleConfig(BaseModel):
    model_config = ConfigDict(extra="allow")

    version: int = 1
    layout_version: int = 2
    timezone: str = "UTC"
    vault_mirror: str | None = None
    models: ConfigModels = Field(default_factory=ConfigModels)
    ollama: OllamaOptions = Field(default_factory=OllamaOptions)
    llm: LlmOptions = Field(default_factory=LlmOptions)
    e2ee: E2eeOptions | None = None


class GraphNode(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    kind: Literal["topic", "entry", "person", "place", "project", "concept"]
    label: str
    weight: float | None = None
    pinned: bool | None = None
    hidden: bool | None = None
    annotation: str | None = None
    entry_id: str | None = None
    ts: str | None = None


class GraphEdge(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    from_: str = Field(alias="from")
    to: str
    rel: Literal["about", "related", "continues", "mentions", "manual"]
    score: float | None = None


class Graph(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: int = 1
    generated: str
    nodes: list[dict[str, Any]] = Field(default_factory=list)
    edges: list[dict[str, Any]] = Field(default_factory=list)


class Enrichment(BaseModel):
    model_config = ConfigDict(extra="allow")

    auto_tags: list[str] = Field(default_factory=list)
    summary_line: str = ""
    entities: list[Any] = Field(default_factory=list)


class EnrichFile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: int = 1
    generated: str
    month: str | None = None
    entries: dict[str, Enrichment] = Field(default_factory=dict)


class TagRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")

    canonical: str
    aliases: list[str] = Field(default_factory=list)
    parent: str | None = None
    count: int = 0


class TagsFile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: int = 1
    generated: str
    tags: list[TagRecord] = Field(default_factory=list)


class Insight(BaseModel):
    model_config = ConfigDict(extra="allow")

    version: int = 1
    date: str
    generated: str
    summary: str = ""
    mood_avg: float | None = None
    themes: list[str] = Field(default_factory=list)
    connections: list[Any] = Field(default_factory=list)
    related_entries: dict[str, list[str]] = Field(default_factory=dict)
    on_this_day: list[str] = Field(default_factory=list)
    time_capsules: list[Any] = Field(default_factory=list)


class CurationOp(BaseModel):
    model_config = ConfigDict(extra="allow")

    op: str
    ts: str
    device: str
    node: str | None = None
    label: str | None = None
    from_: str | None = Field(default=None, alias="from")
    into: str | None = None
    to: str | None = None
    rel: str | None = None
    text: str | None = None
    id: str | None = None

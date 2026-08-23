import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";

type ServeStatus = {
  ready: boolean;
  url: string | null;
  port: number | null;
  message: string;
  error: string | null;
};

const AUTH_HEADER = "X-Chronicle-Token";

const textEl = document.querySelector<HTMLTextAreaElement>("#text")!;
const saveBtn = document.querySelector<HTMLButtonElement>("#save")!;
const cancelBtn = document.querySelector<HTMLButtonElement>("#cancel")!;
const msgEl = document.querySelector<HTMLElement>("#msg")!;

type ReadyServeStatus = ServeStatus & { url: string; port: number };

async function serveStatus(): Promise<ReadyServeStatus> {
  const status = await invoke<ServeStatus>("get_serve_status");
  if (!status.url || status.port == null) {
    throw new Error(status.error || status.message || "Serve is not ready");
  }
  return status as ReadyServeStatus;
}

function baseUrl(status: ReadyServeStatus): string {
  return status.url.replace(/\/$/, "");
}

/**
 * Pairing token from loopback GET /connect (auth-exempt).
 * Returns null when auth is off or the body omits a token — omit the header.
 */
async function fetchLoopbackToken(port: number): Promise<string | null> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/connect`);
    if (!res.ok) return null;
    const body = (await res.json()) as { token?: unknown; auth_required?: unknown };
    if (body.auth_required === false) return null;
    return typeof body.token === "string" && body.token.length > 0 ? body.token : null;
  } catch {
    return null;
  }
}

async function closeCapture(): Promise<void> {
  try {
    await getCurrentWindow().close();
  } catch {
    window.close();
  }
}

cancelBtn.addEventListener("click", () => {
  void closeCapture();
});

saveBtn.addEventListener("click", async () => {
  const text = textEl.value.trim();
  if (!text) {
    msgEl.textContent = "Write something first.";
    return;
  }
  saveBtn.disabled = true;
  msgEl.textContent = "Saving…";
  try {
    const status = await serveStatus();
    const base = baseUrl(status);
    const token = await fetchLoopbackToken(status.port);
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (token) headers[AUTH_HEADER] = token;
    const res = await fetch(`${base}/entries`, {
      method: "POST",
      headers,
      body: JSON.stringify({ type: "log", text, tags: [] }),
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(body || `HTTP ${res.status}`);
    }
    msgEl.textContent = "Saved.";
    await invoke("notify_capture_saved");
    await closeCapture();
  } catch (err) {
    msgEl.textContent = err instanceof Error ? err.message : String(err);
    saveBtn.disabled = false;
  }
});

textEl.addEventListener("keydown", (ev) => {
  if ((ev.metaKey || ev.ctrlKey) && ev.key === "Enter") {
    ev.preventDefault();
    saveBtn.click();
  }
});

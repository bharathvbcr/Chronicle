import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { startRingsLoader } from "./rings";

type ServeStatus = {
  ready: boolean;
  url: string | null;
  port: number | null;
  message: string;
  error: string | null;
};

const statusEl = document.querySelector<HTMLElement>("#status")!;
const detailEl = document.querySelector<HTMLElement>("#detail")!;
const retryBtn = document.querySelector<HTMLButtonElement>("#retry")!;
const canvas = document.querySelector<HTMLCanvasElement>("#boot-canvas")!;

// Boot animation (dependency-free port of the concentric-rings component).
startRingsLoader(canvas, { size: 120, color: "#c45c6a", rings: 4 });

let navigating = false;
let lastError: string | null = null;
const t0 = Date.now();

function elapsed(): string {
  return `${((Date.now() - t0) / 1000).toFixed(1)}s`;
}

function render(status: ServeStatus): void {
  if (navigating) return;

  if (status.ready && status.url) {
    navigating = true;
    statusEl.textContent = "Opening Chronicle…";
    detailEl.textContent = "";
    retryBtn.classList.add("hidden");
    document.body.classList.add("launching");
    // Fade the shell out, then hand the window to the SPA served by the
    // embedded server.
    window.setTimeout(() => window.location.replace(status.url!), 320);
    return;
  }

  const err = status.error ?? "";
  statusEl.textContent = `${status.message || "Starting local server"} · ${elapsed()}`;
  if (err) {
    lastError = err;
    detailEl.textContent = err;
    retryBtn.classList.remove("hidden");
  } else {
    lastError = null;
    detailEl.textContent = "";
    retryBtn.classList.add("hidden");
  }
}

async function poll(): Promise<void> {
  try {
    render(await invoke<ServeStatus>("get_serve_status"));
  } catch (err) {
    if (!lastError && !navigating) {
      statusEl.textContent = `Waiting for Chronicle shell… · ${elapsed()}`;
      detailEl.textContent = err instanceof Error ? err.message : String(err);
    }
  }
}

retryBtn.addEventListener("click", () => {
  retryBtn.disabled = true;
  retryBtn.textContent = "Restarting…";
  void invoke<ServeStatus>("restart_serve")
    .then(render)
    .catch(() => undefined)
    .finally(() => {
      retryBtn.disabled = false;
      retryBtn.textContent = "Try again";
    });
});

void (async () => {
  try {
    await getCurrentWindow().show();
  } catch {
    // not in Tauri (vite preview)
  }

  // Poll first and forever — the event channel is only an accelerator.
  await poll();
  window.setInterval(() => {
    if (!navigating) void poll();
  }, 500);

  // Status pushes from the Rust side; failures here must never take down
  // polling again (this was the frozen-splash bug).
  import("@tauri-apps/api/event")
    .then((m) =>
      m.listen<ServeStatus>("serve-status-changed", (event) => {
        render(event.payload);
      }),
    )
    .catch((err) => console.warn("status events unavailable:", err));
})();

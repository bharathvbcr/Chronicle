//! `chronicle` CLI — serve / process / brain / index / rebuild-markdown-index.

use std::path::PathBuf;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut dir: Option<PathBuf> = None;
    let mut port: u16 = 8765;
    let mut lan = true;
    let mut dry_run = false;
    let mut force = false;
    let mut fix = false;
    let mut format_opt: Option<String> = None;
    let mut positional: Option<String> = None;
    let mut cmd: Option<String> = None;
    let mut it = args.into_iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--version" | "version" => {
                println!("chronicle-server {}", env!("CARGO_PKG_VERSION"));
                return;
            }
            "-v" | "--verbose" => {}
            "--chronicle-dir" => dir = it.next().map(PathBuf::from),
            "--port" => {
                if let Some(p) = it.next().and_then(|v| v.parse().ok()) {
                    port = p;
                }
            }
            "--lan" => lan = true,
            "--no-lan" => lan = false,
            "--host" => {
                it.next();
            }
            "--dry-run" => dry_run = true,
            "--force" => force = true,
            "--fix" | "--apply" => fix = true,
            "--format" => format_opt = it.next(),
            other => {
                if cmd.is_none() && !other.starts_with('-') {
                    cmd = Some(other.to_string());
                } else if !other.starts_with('-') && positional.is_none() {
                    positional = Some(other.to_string());
                }
            }
        }
    }

    let code = match cmd.as_deref() {
        None => {
            print_help();
            0
        }
        Some("serve") => serve(dir, port, lan),
        Some("process") => run_json(|| chronicle_server::pipeline::run_process(
            dir.as_deref(), dry_run, true,
        )),
        Some("brain") => run_json(|| chronicle_server::brain::run_brain_resolved(dir.as_deref(), dry_run)),
        Some("index") => run_json(|| {
            let root = chronicle_server::paths::resolve_chronicle_dir(dir.as_deref())?;
            chronicle_server::index_store::run_index(&root, dry_run, force)
        }),
        Some("backup") => run_json(|| {
            chronicle_server::backup::run_backup(
                dir.as_deref(),
                positional.as_deref().map(std::path::Path::new),
                force,
            )
        }),
        Some("export") => run_json(|| {
            chronicle_server::export::run_export(
                dir.as_deref(),
                format_opt.as_deref(),
                positional.as_deref().map(std::path::Path::new),
            )
        }),
        Some("doctor") => run_json(|| {
            chronicle_server::doctor::run_doctor(dir.as_deref(), fix, dry_run)
        }),
        Some("rollup") => run_json(|| chronicle_server::rollup::run_rollup(dir.as_deref(), dry_run)),
        Some("topics") => run_json(|| chronicle_server::topics::run_topics(dir.as_deref(), dry_run)),
        // Legacy one-shot migration tools remain Python-side by design.
        Some(name @ ("migrate" | "migrate-kb" | "migrate-v2" | "migrate-kb-para" | "migrate-journal-v2" | "cutover-kb" | "import-legacy" | "import-knowledgebase")) => {
            chronicle_server::log_line(
                "ERROR",
                &format!(
                    "'{name}' is a legacy Python-only tool. Run it via the venv:\n  (cd chronicle-pc && ./.venv/bin/chronicle {name} --help)"
                ),
            );
            2
        }
        Some("rebuild-markdown-index") => run_json(|| {
            let root = chronicle_server::paths::resolve_chronicle_dir(dir.as_deref())?;
            chronicle_server::markdown_index::rebuild_markdown_index(&root, dry_run)
        }),
        Some(other) => {
            eprintln!("error: unknown command: {other}\n");
            print_help();
            2
        }
    };
    std::process::exit(code);
}

fn print_help() {
    println!(
        "chronicle {}\n\nUsage: chronicle <command> [flags]\n\nCommands:\n  serve                   Start the local vault server (default port 8765)\n  process                 Run the incremental pipeline\n  brain                   Rebuild enrich/tags/graph/insights\n  index                   Refresh the sqlite search index\n  rebuild-markdown-index  Regenerate _system/index.md
  backup [path]           Zip vault (excludes index/) — --force overwrites
  export [path]           chronosflow bundle — --format chronosflow
  doctor                  Integrity report; --fix repairs entry conflicts
  rollup                  Weekly/monthly/yearly derived notes
  topics                  Topic pages + dream symbols

Legacy migrate*/import* tools: use the python venv CLI.\n\nFlags:\n  --chronicle-dir <dir>   Vault path (default $CHRONICLE_DIR or cwd)\n  --port <port>           Serve port (default 8765)\n  --lan / --no-lan        LAN binding + pairing token (default --lan)\n  --dry-run               Preview writes\n  --force                 Force re-index",
        env!("CARGO_PKG_VERSION")
    );
}

fn serve(dir: Option<PathBuf>, port: u16, lan: bool) -> i32 {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    rt.block_on(async move {
        let config = chronicle_server::serve::ServeConfig {
            chronicle_dir: dir
                .or_else(|| std::env::var("CHRONICLE_DIR").ok().map(PathBuf::from))
                .unwrap_or_else(|| std::env::current_dir().unwrap_or_default()),
            preferred_port: port,
            lan,
            host: "127.0.0.1".into(),
        };
        let (_tx, rx) = tokio::sync::watch::channel(false);
        match chronicle_server::serve::start_server(config, rx).await {
            Ok((_, port)) => {
                chronicle_server::log_line("INFO", &format!("listening on :{port}"));
                // Serve until Ctrl-C triggers graceful shutdown.
                tokio::signal::ctrl_c().await.ok();
                chronicle_server::log_line("INFO", "shutting down");
                0
            }
            Err(e) => {
                chronicle_server::log_line("ERROR", &e);
                1
            }
        }
    })
}

fn run_json<F>(f: F) -> i32
where
    F: FnOnce() -> Result<serde_json::Value, chronicle_server::errors::ChronicleError>,
{
    match f() {
        Ok(v) => {
            println!("{}", serde_json::to_string_pretty(&v).unwrap_or_default());
            0
        }
        Err(e) => {
            chronicle_server::log_line("ERROR", &e.to_string());
            1
        }
    }
}

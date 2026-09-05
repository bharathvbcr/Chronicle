#!/bin/bash
set -e

LAUNCH_AGENTS="$HOME/Library/LaunchAgents"
mkdir -p "$LAUNCH_AGENTS"

SERVE_PLIST="$LAUNCH_AGENTS/com.bharath.chronicle.serve.plist"
WATCH_PLIST="$LAUNCH_AGENTS/com.bharath.chronicle.watch.plist"

cat << 'PLIST_EOF' > "$SERVE_PLIST"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.bharath.chronicle.serve</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Users/bharath/.local/bin/chronicle</string>
        <string>serve</string>
        <string>--port</string>
        <string>8000</string>
        <string>--no-lan</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>CHRONICLE_DIR</key>
        <string>/Users/bharath/Chronicle</string>
        <key>PATH</key>
        <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/bharath/.local/bin</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/Users/bharath/Library/Logs/chronicle-serve.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/bharath/Library/Logs/chronicle-serve.err</string>
</dict>
</plist>
PLIST_EOF

cat << 'PLIST_EOF' > "$WATCH_PLIST"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.bharath.chronicle.watch</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Users/bharath/.local/bin/chronicle</string>
        <string>watch</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>CHRONICLE_DIR</key>
        <string>/Users/bharath/Chronicle</string>
        <key>PATH</key>
        <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/bharath/.local/bin</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/Users/bharath/Library/Logs/chronicle-watch.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/bharath/Library/Logs/chronicle-watch.err</string>
</dict>
</plist>
PLIST_EOF

echo "Wrote launchd plists:"
echo "  $SERVE_PLIST"
echo "  $WATCH_PLIST"

launchctl unload "$SERVE_PLIST" 2>/dev/null || true
launchctl load "$SERVE_PLIST"
launchctl unload "$WATCH_PLIST" 2>/dev/null || true
launchctl load "$WATCH_PLIST"

echo "Loaded com.bharath.chronicle.serve and com.bharath.chronicle.watch into launchctl."

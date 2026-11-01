#!/usr/bin/env bash
# Linux 本地开发入口；Windows 发布请用 build-win32.ps1。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export HM_VOD_HOME="${HM_VOD_HOME:-$SCRIPT_DIR/.local}"
mkdir -p "$HM_VOD_HOME"
if [[ ! -f "$HM_VOD_HOME/config.toml" ]]; then
    cp "$SCRIPT_DIR/config.example.toml" "$HM_VOD_HOME/config.toml"
    echo "已生成初始配置：$HM_VOD_HOME/config.toml"
fi

if [[ "${1:-}" == "--release" ]]; then
    shift
    exec cargo run --release "$@"
fi
exec cargo run "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "→ hapus build & cache"
rm -rf dist node_modules/.vite .capacitor 2>/dev/null || true
find . -name '*.map' -type f -delete 2>/dev/null || true
find . -name '.DS_Store' -delete 2>/dev/null || true
rm -f src/spatial/panel.html 2>/dev/null || true
echo "→ ukuran:"
du -sh . src native 2>/dev/null || true
echo "Selesai."

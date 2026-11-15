#!/usr/bin/env bash
set -euo pipefail
source /workspace/netstrazh-tools/build-env.sh
export BUILD_NUMBER="${BUILD_NUMBER:-35}"
cd /workspace/netstrazh-v3.5-review/sources/Lab-claude-confident-cray-n5kkx8/NetStrazh
exec /workspace/netstrazh-tools/gradle-8.11.1/bin/gradle \
  --no-daemon --max-workers=2 --console=plain \
  --project-cache-dir /workspace/netstrazh-v3.5-review/project-cache \
  -I /workspace/netstrazh-v3.5-review/build-outputs.init.gradle \
  "$@"

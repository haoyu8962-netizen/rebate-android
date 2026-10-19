#!/bin/bash
# 只跑 DS-02；不取网络、不更新依赖、不写 Git。首次基准由实现方核图后 record。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
MODE="${1:-verify}"
case "$MODE" in
  verify) SNAPSHOT_TASK=verifyRoborazziDebug ;;
  record) SNAPSHOT_TASK=recordRoborazziDebug ;;
  *) echo 'usage: bash tools/ci/ds02/run.sh [verify|record]' >&2; exit 2 ;;
esac
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
export PYTHONDONTWRITEBYTECODE=1
python3 tools/ci/ds02/check_oauth_assets.py
python3 tools/ci/ds02/test_oauth_status.py
python3 tools/ci/ds02/check_inventory.py
python3 tools/ci/ds02/test_warning_contract.py
./gradlew --offline --no-daemon --console=plain -p build-logic :convention:test --tests '*Ds02*'
python3 tools/ci/ds02/check_guard.py
./gradlew --offline --no-daemon --console=plain :core:designsystem:testDebugUnitTest --tests '*Ds02*'
./gradlew --offline --no-daemon --console=plain :app:testDebugUnitTest \
  --tests '*Ds02CatalogCoverageTest' --tests '*Ds02CatalogControlsTest' --tests '*Ds02CatalogTokenUsageTest' --tests '*Ds02CatalogLargeFontTest' --tests '*Ds02TitleEllipsisPolicyTest'
./gradlew --offline --no-daemon --console=plain ":app:$SNAPSHOT_TASK" --tests '*Ds02CatalogSnapshotTest'

#!/usr/bin/env bash
# 把仓库里的 git 钩子装进共享的 .git/hooks(所有 worktree 共用)。重复跑无害。
set -euo pipefail
src="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dst="$(git -C "$src" rev-parse --path-format=absolute --git-common-dir)/hooks"
install -m 0755 "$src/pre-push" "$dst/pre-push"
echo "已安装 pre-push → $dst"

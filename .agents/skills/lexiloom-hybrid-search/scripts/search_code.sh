#!/usr/bin/env bash
# ==============================================================================
# LexiLoom 本地代码检索辅助脚本 (search_code.sh)
# 作用：基于 ripgrep / grep 高速检索本地工程，返回精准的文件路径、行号及代码片段
# ==============================================================================

set -euo pipefail

KEYWORD="${1:-}"
if [[ -z "${KEYWORD}" ]]; then
  echo "❌ 用法: $0 <检索关键词或符号名> [扩展名过滤, 如 java|xml|sql]" >&2
  exit 1
fi

EXT_FILTER="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$(pwd)/.lexiloom/config.json" || -d "$(pwd)/.git" || -f "$(pwd)/pom.xml" || -f "$(pwd)/package.json" ]]; then
  PROJECT_ROOT="$(pwd)"
else
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
fi
cd "${PROJECT_ROOT}"

echo "🔍 [Code Search] 正在检索本地代码仓库: \"${KEYWORD}\" ..."

# 1. 如果安装了 ripgrep (rg)
if command -v rg >/dev/null 2>&1; then
  EXCLUDE_ARGS=(
    -g "!**/target/**"
    -g "!**/.git/**"
    -g "!**/node_modules/**"
    -g "!**/.idea/**"
    -g "!**/.gemini/**"
    -g "!**/.lexiloom.env"
  )
  TYPE_ARGS=()
  if [[ -n "${EXT_FILTER}" ]]; then
    TYPE_ARGS=(-g "*.${EXT_FILTER}")
  fi

  MATCHED_FILES=$(rg -l "${EXCLUDE_ARGS[@]}" "${TYPE_ARGS[@]}" -i --fixed-strings "${KEYWORD}" . 2>/dev/null | head -n 10 || true)

  if [[ -z "${MATCHED_FILES}" ]]; then
    echo "⚠️ 未在本地代码库找到与 \"${KEYWORD}\" 匹配的代码。"
    exit 0
  fi

  echo "📁 匹配到以下核心文件："
  echo "${MATCHED_FILES}" | sed 's/^/  - /'
  echo ""
  echo "📄 核心代码段落预览："

  while IFS= read -r file; do
    if [[ -n "${file}" ]]; then
      echo "----------------------------------------------------------------"
      echo "📍 文件: ${file}"
      echo "----------------------------------------------------------------"
      rg -n -C 2 "${EXCLUDE_ARGS[@]}" -i --fixed-strings "${KEYWORD}" "${file}" | head -n 25 || true
      echo ""
    fi
  done <<< "${MATCHED_FILES}"

else
  # 2. 降级使用 grep (macOS / Linux 全兼容)
  INCLUDE_ARGS=()
  if [[ -n "${EXT_FILTER}" ]]; then
    INCLUDE_ARGS=(--include="*.${EXT_FILTER}")
  fi

  GREP_RESULTS=$(grep -rn -i \
    --exclude-dir="target" \
    --exclude-dir=".git" \
    --exclude-dir="node_modules" \
    --exclude-dir=".idea" \
    --exclude-dir=".gemini" \
    --exclude-dir=".lexiloom" \
    "${INCLUDE_ARGS[@]}" \
    "${KEYWORD}" src/ 2>/dev/null | head -n 25 || true)

  if [[ -z "${GREP_RESULTS}" ]]; then
    echo "⚠️ 未在本地代码库 (src/) 找到与 \"${KEYWORD}\" 匹配的代码。"
    exit 0
  fi

  echo "📁 匹配到以下代码行："
  echo "${GREP_RESULTS}" | sed 's/^/  📍 /'
fi

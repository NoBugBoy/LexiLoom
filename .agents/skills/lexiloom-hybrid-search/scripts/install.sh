#!/usr/bin/env bash
# ==============================================================================
# LexiLoom Hybrid Search Skill - 通用多 Agent 技能安装向导
# 支持将技能一键安装至 Antigravity、OpenAI Codex、Claude Code 以及全局环境中
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${SKILL_DIR}/../../.." && pwd)"
SKILL_NAME="lexiloom-hybrid-search"

echo "================================================================="
echo "  🛠️  LexiLoom 混合检索技能 - 通用 Agent 跨平台安装向导"
echo "================================================================="
echo "技能源路径: ${SKILL_DIR}"
echo "工程根目录: ${PROJECT_ROOT}"
echo ""
echo "请选择安装模式："
echo "  [1] 当前工程多 Agent 协同 (为 Antigravity / Codex / Claude Code 建立软链接，推荐)"
echo "  [2] 用户全局安装 (将技能安装到当前用户的 ~/.gemini, ~/.codex, ~/.claude)"
echo "  [3] 同时执行 [1] 和 [2]"
read -r -p "👉 请输入选项 [默认 1]: " CHOICE
CHOICE="${CHOICE:-1}"

install_project() {
  echo ""
  echo "🚀 正在为当前工程配置多 Agent 技能识别通道..."

  # 1. Codex 识别目录
  mkdir -p "${PROJECT_ROOT}/.codex/skills"
  ln -sfn "../../.agents/skills/${SKILL_NAME}" "${PROJECT_ROOT}/.codex/skills/${SKILL_NAME}"
  echo "  ✅ 已挂载 Codex 项目技能: .codex/skills/${SKILL_NAME} -> .agents/skills/${SKILL_NAME}"

  # 2. Claude Code 识别目录
  mkdir -p "${PROJECT_ROOT}/.claude/skills"
  ln -sfn "../../.agents/skills/${SKILL_NAME}" "${PROJECT_ROOT}/.claude/skills/${SKILL_NAME}"
  echo "  ✅ 已挂载 Claude Code 项目技能: .claude/skills/${SKILL_NAME} -> .agents/skills/${SKILL_NAME}"

  # 3. Antigravity 已天然支持 .agents/skills/
  echo "  ✅ Antigravity 已天然识别: .agents/skills/${SKILL_NAME}"
}

install_global() {
  echo ""
  echo "🌐 正在为本机各 Agent 环境配置全局技能..."

  # 1. Antigravity / Gemini 全局
  if [[ -d "${HOME}/.gemini" ]]; then
    mkdir -p "${HOME}/.gemini/config/skills"
    ln -sfn "${SKILL_DIR}" "${HOME}/.gemini/config/skills/${SKILL_NAME}"
    echo "  ✅ 已链接至 Antigravity 全局技能: ~/.gemini/config/skills/${SKILL_NAME}"
  fi

  # 2. Codex 全局
  if [[ -d "${HOME}/.codex" ]]; then
    mkdir -p "${HOME}/.codex/skills"
    ln -sfn "${SKILL_DIR}" "${HOME}/.codex/skills/${SKILL_NAME}"
    echo "  ✅ 已链接至 Codex 全局技能: ~/.codex/skills/${SKILL_NAME}"
  fi

  # 3. Claude Code 全局
  if [[ -d "${HOME}/.claude" ]]; then
    mkdir -p "${HOME}/.claude/skills"
    ln -sfn "${SKILL_DIR}" "${HOME}/.claude/skills/${SKILL_NAME}"
    echo "  ✅ 已链接至 Claude Code 全局技能: ~/.claude/skills/${SKILL_NAME}"
  fi
}

case "${CHOICE}" in
  1)
    install_project
    ;;
  2)
    install_global
    ;;
  3)
    install_project
    install_global
    ;;
  *)
    echo "无效输入，退出。"
    exit 1
    ;;
esac

# 赋予执行权限
chmod +x "${SKILL_DIR}/scripts/"*.sh

echo ""
echo "================================================================="
echo "🎉 安装完成！所有主流 Agent 工具均可无缝识别该技能。"
echo ""
echo "💡 下一步建议："
echo "   若尚未绑定当前工程的知识库工作空间，请运行向导完成配置："
echo "   bash ${SKILL_DIR}/scripts/setup.sh"
echo "================================================================="

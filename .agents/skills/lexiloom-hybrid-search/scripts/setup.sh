#!/usr/bin/env bash
# ==============================================================================
# LexiLoom 项目级知识库工作空间绑定引导向导 (Setup Script)
# 作用：引导用户一键将当前代码仓库与指定的 LexiLoom 工作空间建立关联并持久化
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$(pwd)/.git" || -f "$(pwd)/pom.xml" || -f "$(pwd)/package.json" ]]; then
  PROJECT_ROOT="$(pwd)"
else
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
fi

echo "================================================================="
echo "  🚀 LexiLoom 代码仓库 & 知识库混合检索 - 一键初始化向导"
echo "  （此配置在当前仓库仅需执行一次，团队成员可共享工作空间绑定）"
echo "================================================================="
echo "📍 目标工程根目录: ${PROJECT_ROOT}"
echo ""

CONFIG_DIR="${PROJECT_ROOT}/.lexiloom"
CONFIG_FILE="${CONFIG_DIR}/config.json"
ENV_FILE="${PROJECT_ROOT}/.lexiloom.env"

# 1. 询问/确认 LexiLoom 后端服务地址
DEFAULT_URL="http://localhost:8080"
if [[ -f "${CONFIG_FILE}" ]]; then
  EXISTING_URL=$(grep -o '"serverUrl": *"[^"]*"' "${CONFIG_FILE}" | cut -d'"' -f4 || true)
  if [[ -n "${EXISTING_URL}" ]]; then
    DEFAULT_URL="${EXISTING_URL}"
  fi
fi

read -r -p "👉 请输入 LexiLoom 服务端地址 [默认: ${DEFAULT_URL}]: " INPUT_URL
SERVER_URL="${INPUT_URL:-${DEFAULT_URL}}"
SERVER_URL="${SERVER_URL%/}" # 移除末尾斜杠

# 2. 检查或输入 API Key
API_KEY="${LEXILOOM_API_KEY:-}"
if [[ -f "${ENV_FILE}" ]]; then
  EXISTING_KEY=$(grep -E "^LEXILOOM_API_KEY=" "${ENV_FILE}" | cut -d'=' -f2- || true)
  if [[ -n "${EXISTING_KEY}" ]]; then
    API_KEY="${EXISTING_KEY}"
  fi
fi

if [[ -z "${API_KEY}" ]]; then
  echo ""
  echo "🔑 请配置访问密钥 (API Key)："
  echo "   可前往 LexiLoom Web 端「管理员中心 / 全局设置 → API 密钥」获取 (如 lx_live_xxx)"
  read -r -s -p "👉 请粘贴你的 API Key: " INPUT_KEY
  echo ""
  API_KEY="${INPUT_KEY:-}"
fi

if [[ -z "${API_KEY}" ]]; then
  echo "❌ 错误: API Key 不能为空！" >&2
  exit 1
fi

# 3. 探活并获取可用工作空间列表
echo ""
echo "⏳ 正在连接服务端拉取可用工作空间..."
HTTP_STATUS=0
WORKSPACES_RESP=""

# 尝试调用轻量工作空间查询端点
WORKSPACES_RESP=$(curl -s -w "\n%{http_code}" -X GET "${SERVER_URL}/api/v1/openapi/workspaces" \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Accept: application/json" || true)

HTTP_CODE=$(echo "${WORKSPACES_RESP}" | tail -n1)
BODY_JSON=$(echo "${WORKSPACES_RESP}" | sed '$d')

SELECTED_WS_ID=""
SELECTED_WS_NAME="默认工作空间"

# 解析工作空间列表函数 (优先 Node.js -> Python3 -> jq)
list_workspaces() {
  local json_data="$1"
  if command -v node >/dev/null 2>&1; then
    node -e '
      try {
        const raw = process.argv[1];
        let data = JSON.parse(raw);
        if (data && !Array.isArray(data) && Array.isArray(data.data)) data = data.data;
        if (!Array.isArray(data) || data.length === 0) {
          process.exit(2);
        }
        data.forEach(item => {
          const desc = item.description ? ` (${item.description})` : " (无描述)";
          console.log(`  [${item.id}] ${item.name}${desc}`);
        });
      } catch (e) {
        process.exit(1);
      }
    ' "$json_data"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, json
try:
    data = json.loads(sys.argv[1])
    items = data if isinstance(data, list) else data.get("data", [])
    if not items:
        sys.exit(2)
    for item in items:
        desc = (" (%s)" % item.get("description")) if item.get("description") else " (无描述)"
        print("  [%s] %s%s" % (item.get("id"), item.get("name"), desc))
except Exception:
    sys.exit(1)
' "$json_data"
  elif command -v jq >/dev/null 2>&1; then
    echo "$json_data" | jq -e -r '
      (if type=="array" then . else .data end) |
      if length == 0 then error("empty") else .[] | "  [\(.id)] \(.name) (\(.description // "无描述"))" end
    ' 2>/dev/null
  else
    return 1
  fi
}

# 根据 ID 提取工作空间名称函数
get_workspace_name() {
  local json_data="$1"
  local target_id="$2"
  if command -v node >/dev/null 2>&1; then
    node -e '
      try {
        let data = JSON.parse(process.argv[1]);
        if (data && !Array.isArray(data) && Array.isArray(data.data)) data = data.data;
        const found = data.find(item => String(item.id) === process.argv[2]);
        if (found && found.name) process.stdout.write(found.name);
      } catch (e) {}
    ' "$json_data" "$target_id"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, json
try:
    data = json.loads(sys.argv[1])
    items = data if isinstance(data, list) else data.get("data", [])
    for item in items:
        if str(item.get("id")) == sys.argv[2]:
            sys.stdout.write(str(item.get("name", "")))
            break
except Exception:
    pass
' "$json_data" "$target_id"
  elif command -v jq >/dev/null 2>&1; then
    echo "$json_data" | jq -r --arg id "$target_id" '
      (if type=="array" then . else .data end) | .[] | select((.id|tostring) == $id) | .name
    ' 2>/dev/null || true
  fi
}

if [[ "${HTTP_CODE}" == "200" ]]; then
  set +e
  WS_OUTPUT=$(list_workspaces "${BODY_JSON}")
  PARSE_STATUS=$?
  set -e

  if [[ ${PARSE_STATUS} -eq 0 && -n "${WS_OUTPUT}" ]]; then
    echo "✅ 成功获取工作空间列表："
    echo ""
    echo "${WS_OUTPUT}"
    echo ""
    read -r -p "👉 请输入要绑定到当前代码工程的工作空间 ID [例如 1]: " SELECTED_WS_ID
    SELECTED_WS_NAME=$(get_workspace_name "${BODY_JSON}" "${SELECTED_WS_ID}")
    if [[ -z "${SELECTED_WS_NAME}" ]]; then
      SELECTED_WS_NAME="工作空间-${SELECTED_WS_ID}"
    fi
  elif [[ ${PARSE_STATUS} -eq 2 ]]; then
    echo "⚠️  服务端返回成功 (HTTP 200)，但当前账号下未找到任何可用工作空间。"
    echo "👉 建议先前往 LexiLoom Web 控制台创建空间，或在此手动指定工作空间 ID："
    read -r -p "👉 请输入工作空间 ID [默认 1]: " SELECTED_WS_ID
  else
    echo "ℹ️  提示: 未能自动解析工作空间列表 (HTTP 200)，进入手动指定模式。"
    read -r -p "👉 请直接输入你要绑定的工作空间 ID [例如 1]: " SELECTED_WS_ID
  fi
elif [[ "${HTTP_CODE}" == "401" || "${HTTP_CODE}" == "403" ]]; then
  echo "❌ 提示: 鉴权失败 (HTTP ${HTTP_CODE})，请检查 API Key 是否有效！"
  read -r -p "👉 请输入你要绑定的工作空间 ID [例如 1]: " SELECTED_WS_ID
elif [[ "${HTTP_CODE}" == "404" ]]; then
  echo "ℹ️  提示: 服务端工作空间发现端点 [/api/v1/openapi/workspaces] 尚未就绪 (HTTP 404)，进入手动指定模式。"
  read -r -p "👉 请直接输入你要绑定的工作空间 ID [例如 1]: " SELECTED_WS_ID
else
  echo "ℹ️  提示: 连接服务端异常 (HTTP ${HTTP_CODE})，进入手动指定模式。"
  read -r -p "👉 请直接输入你要绑定的工作空间 ID [例如 1]: " SELECTED_WS_ID
fi

if [[ -z "${SELECTED_WS_ID}" ]]; then
  SELECTED_WS_ID="1"
fi

# 4. 创建 .lexiloom 配置目录与文件
mkdir -p "${CONFIG_DIR}"

cat > "${CONFIG_FILE}" <<EOF
{
  "version": "1.0.0",
  "serverUrl": "${SERVER_URL}",
  "workspace": {
    "id": ${SELECTED_WS_ID},
    "name": "${SELECTED_WS_NAME}"
  },
  "search": {
    "useWorkspaceDefaults": true,
    "codeScope": [
      "src/main/java",
      "src/main/resources"
    ],
    "excludePatterns": [
      "**/target/**",
      "**/.git/**",
      "**/node_modules/**"
    ]
  }
}
EOF

# 写入本地密钥（禁止入库）
cat > "${ENV_FILE}" <<EOF
# LexiLoom Local API Credentials (DO NOT COMMIT TO GIT)
LEXILOOM_API_KEY=${API_KEY}
EOF

# 5. 防御性检查 .gitignore
GITIGNORE="${PROJECT_ROOT}/.gitignore"
if [[ -f "${GITIGNORE}" ]]; then
  if ! grep -q "^\.lexiloom\.env" "${GITIGNORE}"; then
    echo "" >> "${GITIGNORE}"
    echo "# LexiLoom 本地私密凭证" >> "${GITIGNORE}"
    echo ".lexiloom.env" >> "${GITIGNORE}"
    echo "✅ 已自动向 .gitignore 追加入库保护规则: .lexiloom.env"
  fi
fi

echo ""
echo "================================================================="
echo "🎉 初始化完成！"
echo "  📁 项目配置已生成: .lexiloom/config.json (可安全提交到 Git 团队共享)"
echo "  🔒 私有凭证已生成: .lexiloom.env (已自动添加到 .gitignore 保护)"
echo "  🎯 已绑定工作空间: ID=${SELECTED_WS_ID} (${SELECTED_WS_NAME})"
echo "================================================================="

#!/usr/bin/env bash
# ==============================================================================
# LexiLoom 知识库检索辅助脚本 (search_knowledge.sh)
# 作用：调用 LexiLoom 后端语义检索 API，返回结构化文档切片、标题与相似度得分
# ==============================================================================

set -euo pipefail

QUERY="${1:-}"
if [[ -z "${QUERY}" ]]; then
  echo "❌ 用法: $0 <自然语言检索问题> [可选 topK, 默认 5]" >&2
  exit 1
fi

TOP_K="${2:-5}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$(pwd)/.lexiloom/config.json" || -d "$(pwd)/.git" || -f "$(pwd)/pom.xml" || -f "$(pwd)/package.json" ]]; then
  PROJECT_ROOT="$(pwd)"
else
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
fi

CONFIG_FILE="${PROJECT_ROOT}/.lexiloom/config.json"
ENV_FILE="${PROJECT_ROOT}/.lexiloom.env"

# 1. 检查配置
if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "⚠️ 尚未找到项目配置文件 (.lexiloom/config.json)。"
  echo "👉 请先执行绑定脚本: bash ${SCRIPT_DIR}/setup.sh"
  exit 1
fi

SERVER_URL=$(grep -o '"serverUrl": *"[^"]*"' "${CONFIG_FILE}" | cut -d'"' -f4 || echo "http://localhost:8080")
WORKSPACE_ID=$(grep -o '"id": *[0-9]*' "${CONFIG_FILE}" | head -n1 | grep -o '[0-9]*' || echo "1")
WORKSPACE_NAME=$(grep -o '"name": *"[^"]*"' "${CONFIG_FILE}" | head -n1 | cut -d'"' -f4 || echo "工作空间-${WORKSPACE_ID}")

API_KEY="${LEXILOOM_API_KEY:-}"
if [[ -f "${ENV_FILE}" ]]; then
  EXISTING_KEY=$(grep -E "^LEXILOOM_API_KEY=" "${ENV_FILE}" | cut -d'=' -f2- || true)
  if [[ -n "${EXISTING_KEY}" ]]; then
    API_KEY="${EXISTING_KEY}"
  fi
fi

if [[ -z "${API_KEY}" ]]; then
  echo "❌ 错误: 未读取到 API Key。请在 .lexiloom.env 中配置或导出环境变量 LEXILOOM_API_KEY" >&2
  exit 1
fi

echo "📚 [Knowledge Search] 正在检索 LexiLoom 知识库 [空间: ${WORKSPACE_NAME}(ID:${WORKSPACE_ID})]: \"${QUERY}\" ..."

# 2. 发起轻量无头检索请求
RESPONSE=$(curl -s -w "\n%{http_code}" -X GET \
  "${SERVER_URL}/api/v1/openapi/knowledge/search" \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "X-Call-Source: CLI_SKILL" \
  -H "Accept: application/json" \
  -G \
  --data-urlencode "q=${QUERY}" \
  --data-urlencode "workspaceId=${WORKSPACE_ID}" \
  --data-urlencode "topK=${TOP_K}" || true)

HTTP_CODE=$(echo "${RESPONSE}" | tail -n1)
BODY=$(echo "${RESPONSE}" | sed '$d')

# 格式化检索切片输出函数
format_search_results() {
  local json_data="$1"
  if command -v node >/dev/null 2>&1; then
    node -e '
      try {
        const raw = process.argv[1];
        let data = JSON.parse(raw);
        const results = data.results || (Array.isArray(data) ? data : []);
        console.log(`✅ 成功检索到 ${results.length} 条相关知识库切片：\n`);
        results.forEach(item => {
          const title = item.documentTitle || "无标题";
          const score = item.score !== undefined ? item.score : "N/A";
          const chunkIdx = item.chunkIndex !== undefined ? item.chunkIndex : 0;
          const docId = item.documentId || "N/A";
          const content = (item.content || "").replace(/\n/g, " ");
          console.log(`📄 文档: 《${title}》 [相关度得分: ${score}]`);
          console.log(`   切片序号: #${chunkIdx} | 文档ID: ${docId}`);
          console.log(`   摘要摘录: ${content}`);
          console.log("----------------------------------------------------------------");
        });
      } catch(e) {
        console.log("✅ 检索成功 (原始 JSON 输出)：");
        console.log(process.argv[1]);
      }
    ' "$json_data"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, json
try:
    data = json.loads(sys.argv[1])
    results = data.get("results", data if isinstance(data, list) else [])
    print("✅ 成功检索到 %d 条相关知识库切片：\n" % len(results))
    for item in results:
        title = item.get("documentTitle", "无标题")
        score = item.get("score", "N/A")
        chunk_idx = item.get("chunkIndex", 0)
        doc_id = item.get("documentId", "N/A")
        content = (item.get("content") or "").replace("\n", " ")
        print("📄 文档: 《%s》 [相关度得分: %s]" % (title, score))
        print("   切片序号: #%s | 文档ID: %s" % (chunk_idx, doc_id))
        print("   摘要摘录: %s" % content)
        print("----------------------------------------------------------------")
except Exception:
    print("✅ 检索成功 (原始 JSON 输出)：")
    print(sys.argv[1])
' "$json_data"
  elif command -v jq >/dev/null 2>&1; then
    COUNT=$(echo "${json_data}" | jq -r '.results | length' 2>/dev/null || echo "0")
    echo "✅ 成功检索到 ${COUNT} 条相关知识库切片："
    echo ""
    echo "${json_data}" | jq -r '.results[] | "📄 文档: 《\(.documentTitle)》 [相关度得分: \(.score // "N/A")]\n   切片序号: #\(.chunkIndex // 0) | 文档ID: \(.documentId)\n   摘要摘录: \(.content | gsub("\n"; " "))\n----------------------------------------------------------------"'
  else
    echo "✅ 检索成功 (原始 JSON 输出)："
    echo "${json_data}"
  fi
}

if [[ "${HTTP_CODE}" == "200" ]]; then
  format_search_results "${BODY}"
elif [[ "${HTTP_CODE}" == "404" ]]; then
  echo "⚠️ 提示: 后端 OpenAPI 检索端点 [/api/v1/openapi/knowledge/search] 尚未就绪 (HTTP 404)。"
  echo "👉 建议先在后端实现该 MCP/Skill 专用无状态检索 API。"
else
  echo "❌ 知识库检索请求失败 (HTTP ${HTTP_CODE}):"
  echo "${BODY}"
  exit 1
fi

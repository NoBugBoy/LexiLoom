#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
当 Semgrep 代码安全与规约扫描失败时，解析 semgrep.json 并向飞书群推送报警卡片。
无需任何第三方依赖，纯 Python 原生库实现。
"""

import os
import json
import urllib.request


def send_feishu_alert():
    webhook_url = os.environ.get("FEISHU_WEBHOOK_URL", "").strip()
    if not webhook_url:
        print("⚠️ [Feishu] 未检测到 FEISHU_WEBHOOK_URL 环境变量，跳过报警卡片发送。")
        return

    repo = os.environ.get("GITHUB_REPOSITORY", "NoBugBoy/LexiLoom")
    ref = os.environ.get("GITHUB_REF_NAME", "unknown")
    actor = os.environ.get("GITHUB_ACTOR", "unknown")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    run_url = f"https://github.com/{repo}/actions/runs/{run_id}" if run_id else f"https://github.com/{repo}"

    # 解析 semgrep.json 获取具体违规项
    findings = []
    report_file = "semgrep.json"
    if os.path.exists(report_file):
        try:
            with open(report_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                results = data.get("results", [])
                for r in results:
                    rule_id = r.get("check_id", "Unknown-Rule")
                    # 简化规则名称，只保留最后一段标识
                    short_rule = rule_id.split(".")[-1] if "." in rule_id else rule_id
                    path = r.get("path", "")
                    start_line = r.get("start", {}).get("line", 0)
                    msg = r.get("extra", {}).get("message", "").strip()
                    # 截断过长的说明
                    if len(msg) > 100:
                        msg = msg[:97] + "..."
                    severity = r.get("extra", {}).get("severity", "ERROR")
                    findings.append(
                        f"• **[{severity}]** `{path}:{start_line}`\n"
                        f"  **规约：** `{short_rule}`\n"
                        f"  **说明：** {msg}"
                    )
        except Exception as e:
            print(f"⚠️ 解析 {report_file} 失败: {e}")

    total_count = len(findings)
    if total_count > 0:
        display_findings = findings[:6]
        more_text = f"\n\n*...等共 {total_count} 处违规，请点击下方按钮查看完整日志*" if total_count > 6 else ""
        findings_str = "\n\n".join(display_findings) + more_text
    else:
        findings_str = "扫描命令执行异常中断，未获取到结构化报告。请查看 Actions 终端输出日志。"

    summary_md = (
        f"**📦 仓库：** `{repo}`\n"
        f"**🌿 分支：** `{ref}`\n"
        f"**👤 提交人：** `{actor}`\n"
        f"**❌ 拦截违规项：** **{total_count}** 处\n\n"
        f"---\n"
        f"**📋 拦截详情：**\n{findings_str}"
    )

    card_payload = {
        "msg_type": "interactive",
        "card": {
            "config": {
                "wide_screen_mode": True
            },
            "header": {
                "title": {
                    "tag": "plain_text",
                    "content": "🚨 [代码安全门禁] Semgrep 规约与安全扫描拦截告警"
                },
                "template": "red"
            },
            "elements": [
                {
                    "tag": "div",
                    "text": {
                        "tag": "lark_md",
                        "content": summary_md
                    }
                },
                {
                    "tag": "action",
                    "actions": [
                        {
                            "tag": "button",
                            "text": {
                                "tag": "plain_text",
                                "content": "🔍 前往查看 Actions 运行日志"
                            },
                            "type": "danger",
                            "url": run_url
                        }
                    ]
                }
            ]
        }
    }

    try:
        req = urllib.request.Request(
            webhook_url,
            data=json.dumps(card_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"✅ [Feishu] 飞书安全拦截告警已发送，HTTP 状态码: {resp.status}")
    except Exception as e:
        print(f"❌ [Feishu] 发送飞书告警卡片失败: {e}")


if __name__ == "__main__":
    send_feishu_alert()

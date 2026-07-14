#!/usr/bin/env python3
"""
CData Agent 工具调用成功率测试工具
===================================
通过 HTTP API 发送 100 个预设问题，解析 SSE 流式响应，
统计真实的工具调用成功率和故障分布。

原理：
  后端每个工具调用失败时，会返回 {"error":"type","message":"..."} 结构化错误，
  LLM 会将这些错误复述到回复文本中。本脚本通过：
  1. 解析 SSE complete 事件 → 判断是否正常结束
  2. 扫描回复文本中的错误关键词 → 判断失败类型
  3. 结合 tokenUsage 和 chartOption 综合判定

用法:
  1. 启动后端: 在 backend 目录下 mvn spring-boot:run
  2. 运行本脚本: python3 test_tool_success_rate.py
  3. 等待输出结果（预计 20-40 分钟）
"""

import requests
import json
import time
import re
import sys
import os
from collections import Counter, defaultdict
from datetime import datetime

BASE = "http://localhost:8080/apis"
TEST_FILES_DIR = os.path.dirname(os.path.abspath(__file__)) + "/CDataAgent_Backend/src"

# ─── 已知错误关键词（从 ToolResultUtils 错误协议和 prompt 错误处理中提取） ───
ERROR_PATTERNS = [
    # SQL 语法/列名错误
    (r"查询语法错误", "syntax"),
    (r"列名或表名不存在", "syntax"),
    (r"语法错误", "syntax"),
    (r"syntax error", "syntax"),
    (r"Parser Error", "syntax"),
    (r"Binder Error", "syntax"),
    (r"not found", "syntax"),
    (r"does not exist", "syntax"),
    # 超时
    (r"查询超时", "timeout"),
    # 系统异常
    (r"系统异常", "system"),
    (r"数据引擎异常", "system"),
    (r"连接异常", "system"),
    # 文件加载问题
    (r"没有已加载的数据文件", "syntax"),
    (r"未指定数据文件", "syntax"),
    (r"文件加载失败", "system"),
    (r"分析目标不明确", "syntax"),
    # 图表
    (r"图表构建失败", "system"),
    (r"数据引用不存在", "syntax"),
    # Agent 通用
    (r"处理失败", "system"),
]

# ─── 工具调用关键词（用于估算工具调用次数） ───
TOOL_MENTIONS = [
    r"loadData",
    r"runDuckdb",
    r"queryStatistics",
    r"runPython",
    r"getSchema",
    r"getAnalysisState",
    r"buildChart",
    r"validateChart",
]


def upload_file(session, conversation_id, filepath):
    """上传单个文件，返回文件 ID"""
    filename = os.path.basename(filepath)
    with open(filepath, "rb") as f:
        resp = session.post(
            f"{BASE}/file/upload",
            params={"conversationId": conversation_id, "replaceIfExists": "true"},
            files={"files": (filename, f, "text/csv")},
            timeout=60,
        )
    data = resp.json()
    if data.get("code") != 0:
        raise Exception(f"上传失败: {data.get('message')}")
    ids = []
    for item in data.get("data", []):
        ids.append(str(item["id"]))
        print(f"  ✓ {item['originalFilename']} → fileId={item['id']} ({item.get('rowCount', '?')}行)")
    return ids


def parse_sse(url, params, timeout=180):
    """
    消费 SSE 流，返回:
      - text: 完整回复文本
      - chart_option: 图表 JSON（如有）
      - token_usage: token 消耗（如有）
      - error: 异常信息（如有）
      - complete: 是否正常完成
    """
    result = {"text": "", "chart_option": None, "token_usage": None, "error": None, "complete": False}

    try:
        resp = requests.post(url, params=params, stream=True, timeout=timeout)
        resp.encoding = "utf-8"

        current_event = {}
        for line in resp.iter_lines(decode_unicode=True):
            if line is None:
                continue
            if line.startswith("event:"):
                current_event["event"] = line[6:].strip()
            elif line.startswith("data:"):
                current_event["data"] = line[5:].strip()
            elif line == "":
                # 一个 SSE 事件完成
                ev = current_event.get("event")
                data = current_event.get("data", "")

                if ev == "message":
                    result["text"] += data
                elif ev == "chart":
                    try:
                        result["chart_option"] = json.loads(data)
                    except json.JSONDecodeError:
                        pass
                elif ev == "complete":
                    result["complete"] = True
                    try:
                        parsed = json.loads(data)
                        if parsed.get("type") == "error":
                            result["error"] = parsed.get("message", "后端处理错误")
                        else:
                            result["token_usage"] = parsed.get("tokenUsage")
                            if parsed.get("chartOption"):
                                try:
                                    result["chart_option"] = json.loads(parsed["chartOption"])
                                except (json.JSONDecodeError, TypeError):
                                    pass
                    except json.JSONDecodeError:
                        pass
                elif ev == "error":
                    result["error"] = data or "SSE 错误事件"

                current_event = {}

    except requests.Timeout:
        result["error"] = "HTTP 超时"
    except requests.ConnectionError:
        result["error"] = "连接失败"
    except Exception as e:
        result["error"] = str(e)

    return result


def detect_errors(text):
    """扫描文本中是否包含已知错误模式，返回 [(error_type, matched_pattern), ...]"""
    found = []
    for pattern, err_type in ERROR_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            found.append(err_type)
    return found


def estimate_tool_calls(text):
    """基于文本中提及的工具名称估算工具调用次数"""
    count = 0
    for pattern in TOOL_MENTIONS:
        count += len(re.findall(pattern, text, re.IGNORECASE))
    return count


def classify_query(message):
    """根据消息内容分类查询类型"""
    # 闲聊
    chitchat_keywords = ["你好", "谢谢", "再见", "hi", "hello", "你是谁", "你能做什么"]
    for kw in chitchat_keywords:
        if kw in message.lower():
            return "闲聊/问候"
    # 模糊
    vague_keywords = ["看看", "分析一下", "帮我看看", "有什么"]
    for kw in vague_keywords:
        if kw in message:
            return "模糊意图"
    # 图表
    if "画" in message or "图" in message or "chart" in message.lower():
        return "图表生成"
    # 复杂 SQL（含 WHERE 或筛选）
    if "筛选" in message or "条件" in message or "where" in message.lower():
        return "复杂SQL"
    # 统计
    if "统计" in message or "分布" in message or "describe" in message.lower():
        return "统计函数"
    # 多文件
    if "关联" in message or "join" in message.lower() or "对比" in message:
        return "多文件关联"
    # 多步
    if "先" in message and "再" in message:
        return "多步分析"
    return "基础分析查询"


def run_test(session, conversation_id, file_ids, test_cases):
    """执行所有测试用例，返回结果列表"""
    results = []

    for i, case in enumerate(test_cases, 1):
        message = case["message"]
        selected_ids = case.get("files", [])
        category = classify_query(message)

        # 构建 fileIds 参数
        use_file_ids = []
        for key in selected_ids:
            if key in file_ids:
                use_file_ids.extend(file_ids[key])

        params = {"message": message}
        if use_file_ids:
            params["fileIds"] = ",".join(use_file_ids)

        # 开始计时
        start = time.time()
        resp = parse_sse(f"{BASE}/agent/chat/stream", params)
        elapsed = time.time() - start

        # 提取文本中的错误
        text_errors = detect_errors(resp["text"])

        # 综合判定是否成功
        has_error_event = resp["error"] is not None
        has_text_error = len(text_errors) > 0
        has_complete = resp["complete"]
        has_token = resp["token_usage"] is not None
        has_chart = resp["chart_option"] is not None

        # 成功条件：正常完成 + 有 token 消耗 + 无文本错误
        success = has_complete and has_token and not has_text_error

        # 汇总错误类型
        all_errors = text_errors
        if resp["error"]:
            all_errors.append("connection:" + resp["error"])

        # 工具调用估算
        tool_count = estimate_tool_calls(resp["text"])

        result = {
            "index": i,
            "category": category,
            "message": message[:60],
            "success": success,
            "complete": has_complete,
            "has_token": has_token,
            "has_chart": has_chart,
            "tool_count": tool_count,
            "errors": all_errors,
            "token_usage": resp["token_usage"],
            "elapsed": round(elapsed, 1),
            "text_preview": resp["text"][:100].replace("\n", " "),
        }

        results.append(result)

        # 进度输出
        status = "✓" if success else "✗"
        print(f"  [{i:3d}/100] {status} {category} | {elapsed:5.1f}s | token={resp['token_usage'] or '-'} | {message[:50]}")

    return results


def print_report(results):
    """输出测试报告"""
    total = len(results)
    successes = [r for r in results if r["success"]]
    failures = [r for r in results if not r["success"]]
    success_rate = len(successes) / total * 100 if total > 0 else 0

    # 按场景统计
    by_category = defaultdict(list)
    for r in results:
        by_category[r["category"]].append(r)

    # 错误分布
    error_counter = Counter()
    for r in failures:
        for e in r["errors"]:
            error_counter[e] += 1

    # Token 汇总
    tokens = [r["token_usage"] for r in results if r["token_usage"] is not None]
    avg_tokens = sum(tokens) / len(tokens) if tokens else 0

    # 工具调用汇总
    tool_counts = [r["tool_count"] for r in successes]
    avg_tools = sum(tool_counts) / len(tool_counts) if tool_counts else 0

    # ── 输出报告 ──
    print("\n" + "=" * 70)
    print("  CData Agent 工具调用成功率测试报告")
    print("=" * 70)
    print(f"  测试时间:    {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  总测试数:    {total}")
    print(f"  成功:        {len(successes)}")
    print(f"  失败:        {len(failures)}")
    print(f"  成功率:      {success_rate:.1f}%")
    print(f"  平均耗时:    {sum(r['elapsed'] for r in results)/total:.1f}s")
    print(f"  平均 Token:  {avg_tokens:.0f}")
    print(f"  平均工具调用: {avg_tools:.1f} 次/轮")
    print()

    # 场景成功率
    print(f"  {'场景':<16} {'次数':>4} {'成功':>4} {'成功率':>8} {'平均耗时':>8}")
    print(f"  {'-'*16} {'-'*4} {'-'*4} {'-'*8} {'-'*8}")
    for cat in ["基础分析查询", "多文件关联", "多步分析", "图表生成", "复杂SQL", "统计函数", "模糊意图", "闲聊/问候"]:
        items = by_category.get(cat, [])
        if not items:
            continue
        cat_success = sum(1 for r in items if r["success"])
        rate = cat_success / len(items) * 100
        avg_t = sum(r["elapsed"] for r in items) / len(items)
        print(f"  {cat:<16} {len(items):>4} {cat_success:>4} {rate:>7.1f}% {avg_t:>7.1f}s")

    print()

    # 错误分布
    print(f"  ── 错误分布 (共 {sum(error_counter.values())} 次) ──")
    for err_type, count in error_counter.most_common():
        pct = count / len(failures) * 100 if failures else 0
        print(f"    {err_type:<16} {count:>3} 次 ({pct:.0f}%)")

    print()

    # 失败详情
    if failures:
        print(f"  ── 失败详情 (前 20 条) ──")
        for r in failures[:20]:
            err_str = ", ".join(r["errors"][:3])
            print(f"    #{r['index']:3d} [{r['category']}] {r['message'][:40]:<40s} | {err_str:<20s} | {r['elapsed']:5.1f}s")

    # 写入详细结果 JSON
    report_file = f"test_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump({
            "total": total,
            "success": len(successes),
            "fail": len(failures),
            "success_rate": round(success_rate, 1),
            "avg_elapsed": round(sum(r['elapsed'] for r in results)/total, 1),
            "avg_tokens": round(avg_tokens),
            "avg_tool_calls": round(avg_tools, 1),
            "error_distribution": dict(error_counter.most_common()),
            "by_category": {
                cat: {
                    "total": len(items),
                    "success": sum(1 for r in items if r["success"]),
                    "success_rate": round(sum(1 for r in items if r["success"]) / len(items) * 100, 1) if items else 0,
                }
                for cat, items in by_category.items()
            },
            "details": results,
        }, f, ensure_ascii=False, indent=2)
    print(f"\n  详细报告已保存: {report_file}")
    print("=" * 70)


def main():
    print("=" * 70)
    print("  CData Agent 工具调用成功率测试")
    print("=" * 70)

    # ── Step 1: 检查后端 ──
    print("\n[Step 1/4] 检查后端连接...")
    try:
        resp = requests.get(f"{BASE}/agent/conversation", timeout=5)
        conversation_id = resp.json()["data"]
        print(f"  后端正常，conversationId={conversation_id}")
    except Exception as e:
        print(f"  ✗ 后端连接失败: {e}")
        print("  请先执行 mvn spring-boot:run 启动后端")
        sys.exit(1)

    # ── Step 2: 上传测试文件 ──
    print(f"\n[Step 2/4] 上传测试文件 (from {TEST_FILES_DIR})...")

    upload_files = {
        "appearances": "appearances_clean.csv",
        "player_info": "player_info.csv",
        "transfers": "transfer_final.csv",
        "clubs": "clubs_clean.csv",
    }

    session = requests.Session()
    file_ids = {}

    for key, filename in upload_files.items():
        filepath = os.path.join(TEST_FILES_DIR, filename)
        if not os.path.exists(filepath):
            print(f"  ⚠ {filename} 不存在，跳过")
            continue
        print(f"  上传 {filename}...")
        try:
            ids = upload_file(session, conversation_id, filepath)
            file_ids[key] = ids
        except Exception as e:
            print(f"  ✗ 上传失败: {e}")

    if not file_ids.get("appearances"):
        print("  ✗ 主数据文件上传失败，无法继续")
        sys.exit(1)

    # ── Step 3: 生成 100 个测试用例 ──
    print(f"\n[Step 3/4] 生成 100 个测试用例...")

    test_cases = []

    # A. 基础分析查询 (40 次) — appearances 文件
    metrics = ["goals", "assists", "yellow_cards", "red_cards", "minutes_played"]
    dims = ["player_name", "competition_id", "player_club_id"]
    aggs = ["总和", "平均值", "数量", "最大值"]
    for metric in metrics:
        for dim in dims[:2]:
            for agg in aggs[:2]:
                if len(test_cases) >= 40:
                    break
                test_cases.append({
                    "message": f"统计各{dim}的{metric}{agg}，按降序排列",
                    "files": ["appearances"]
                })
            if len(test_cases) >= 40:
                break
        if len(test_cases) >= 40:
            break

    # B. 多文件关联 (10 次)
    join_queries = [
        "关联出场数据和球员信息，统计各位置球员的总进球数",
        "关联出场数据和球员信息，分析不同位置球员的平均出场时间",
        "关联转会数据和球员信息，分析各位置球员的平均转会费",
        "关联出场数据和球员信息，统计各国籍球员的进球数",
        "关联出场数据和球队数据，统计各联赛的出场次数",
        "关联转会数据和球队数据，分析各联赛的转会支出",
        "关联出场数据和球员信息，分析身高与进球数的关系",
        "关联出场数据和球员信息，统计左右脚球员的助攻数差异",
        "关联转会数据和球员信息，找出转会费最高的球员及其位置",
        "关联出场数据和球队数据，对比不同阵容规模球队的进球效率",
    ]
    for q in join_queries:
        test_cases.append({"message": q, "files": ["appearances", "player_info"]})

    # C. 多步分析 (10 次)
    multi_queries = [
        "先统计各联赛的总进球数，再找出进球最多的前5个联赛",
        "先计算每个球员的平均进球数，再按位置分组对比",
        "先统计各月的总进球趋势，再找出进球最多的月份",
        "先看各球队的犯规数据，再对比红黄牌分布",
        "先统计不同 competition_id 的场均进球，再排序",
        "先看各球员的助攻排名，再分析前10名的出场时间",
        "先统计每分钟进球效率，再按国籍分组",
        "先计算每场比赛的平均进球，再按赛季趋势分析",
        "先找出红牌最多的球员，再分析他的犯规规律",
        "先统计各联赛的出场总时间，再计算场均时间排名",
    ]
    for q in multi_queries:
        test_cases.append({"message": q, "files": ["appearances"]})

    # D. 图表生成 (10 次)
    chart_queries = [
        "画个柱状图展示各联赛的总进球数",
        "用折线图按日期展示进球趋势",
        "做个饼图看各联赛的出场次数占比",
        "画个柱状图比较各联赛的平均进球和平均助攻",
        "做个散点图分析出场时间和进球数的关系",
        "画个雷达图对比不同联赛的进球、助攻、犯规数据",
        "用柱状图展示红牌最多的前10个联赛",
        "画个折线图按月统计总出场时间趋势",
        "做个饼图展示不同球员位置的进球占比",
        "画个柱状图对比各联赛每90分钟进球效率",
    ]
    for q in chart_queries:
        test_cases.append({"message": q, "files": ["appearances"]})

    # E. 复杂 SQL (10 次) — 含筛选条件
    complex_queries = [
        "筛选出场时间大于500分钟的球员，统计他们的进球总数",
        "统计进球数大于5的球员有哪些，按联赛分组",
        "找出出场时间超过1000分钟且红牌数为0的球员",
        "按日期筛选2020年后的数据，统计各赛季的场均进球",
        "统计单场比赛进球超过2个的比赛场次占比",
        "找出总助攻数前10的球员中，谁的出场时间最少",
        "统计黄牌数大于红牌数10倍的球员分布",
        "按联赛和赛季两个维度统计场均进球",
        "查找出场时间超过平均值的球员的进球分布",
        "统计进球数、助攻数、出场时间三项指标都高于平均的球员",
    ]
    for q in complex_queries:
        test_cases.append({"message": q, "files": ["appearances"]})

    # F. 统计函数 (10 次) — 触发 queryStatistics
    stats_queries = [
        "统计进球数的分布情况（均值、最大、最小、标准差）",
        "统计出场时间的分布情况",
        "统计助攻数的分布情况",
        "统计红牌数的分布情况",
        "统计黄牌数的分布情况",
        "describe 所有数值列的统计量",
        "给出 minutes_played 的百分位数分布",
        "给出 goals 和 assists 的统计描述",
        "统计各数值列的中位数和四分位距",
        "计算 goals 的 PERCENTILE 分布",
    ]
    for q in stats_queries:
        test_cases.append({"message": q, "files": ["appearances"]})

    # G. 模糊意图 (5 次)
    vague_queries = [
        "看看这些数据",
        "帮我分析一下",
        "有什么 interesting 的发现",
        "这个数据能看出什么",
        "随便看看有什么规律",
    ]
    for q in vague_queries:
        test_cases.append({"message": q, "files": ["appearances"]})

    # H. 闲聊/问候 (5 次)
    chitchat_queries = [
        "你好",
        "谢谢",
        "再见",
        "你是谁？",
        "你能做什么？",
    ]
    for q in chitchat_queries:
        test_cases.append({"message": q})

    # 裁剪到正好 100 条
    test_cases = test_cases[:100]
    print(f"  生成了 {len(test_cases)} 个测试用例")

    # 统计各类别数量
    cat_counts = Counter(classify_query(c["message"]) for c in test_cases)
    for cat, cnt in cat_counts.most_common():
        print(f"    {cat}: {cnt} 次")

    # ── Step 4: 执行测试 ──
    print(f"\n[Step 4/4] 开始执行测试 (预计用时 20-40 分钟)...")
    print(f"  {'='*60}")

    results = run_test(session, conversation_id, file_ids, test_cases)

    # ── 输出报告 ──
    print(f"\n  {'='*60}")
    print_report(results)


if __name__ == "__main__":
    main()

#!/bin/bash
# CData Agent 工具调用成功率测试 (Windows/Git Bash兼容版)
# 用法: cd CDataAgent_Backend && bash run_tests.sh

set -o pipefail

BASE="http://localhost:8080/apis"
RESULTS_FILE="test_results_$$.txt"

echo "=== Step 1: 获取对话ID ==="
CONV_ID=$(curl -s "$BASE/agent/conversation" | sed 's/.*"data":"\([^"]*\)".*/\1/')
echo "conversationId=$CONV_ID"

echo "=== Step 2: 上传测试文件 ==="
UPLOAD_RESP=$(curl -s -X POST "$BASE/file/upload?conversationId=$CONV_ID&replaceIfExists=true" \
  -F "files=@src/appearances_clean.csv" \
  -F "files=@src/player_info.csv")

# 提取文件ID (兼容Windows Git Bash: 不用 -oP)
APPEARANCES_ID=$(echo "$UPLOAD_RESP" | sed 's/.*"id":"\([0-9]*\)".*/\1/' | head -1)
PLAYER_ID=$(echo "$UPLOAD_RESP" | sed 's/.*"id":"\([0-9]*\)".*/\1/' | tail -1)
echo "appearances_id=$APPEARANCES_ID  player_id=$PLAYER_ID"
echo ""

echo "=== Step 3: 执行测试 ==="
echo "idx|category|message|success|error_type|has_token|has_chart|elapsed" > "$RESULTS_FILE"

# ─── 测试用例 ───
TESTS=()
add_test() { TESTS+=("$1|$2|$3"); }

# A: 基础分析 (25)
for dim in "player_name" "competition_id"; do
    for metric in "goals" "assists" "yellow_cards"; do
        for agg in "总和" "平均值"; do
            add_test "基础分析" "统计各${dim}的${metric}${agg}，按降序排列" "$APPEARANCES_ID"
        done
    done
done
add_test "基础分析" "各联赛的总出场时间排名" "$APPEARANCES_ID"
add_test "基础分析" "哪个球员的进球最多" "$APPEARANCES_ID"
add_test "基础分析" "各联赛的红牌总数排名" "$APPEARANCES_ID"
add_test "基础分析" "统计各球员的平均出场时间" "$APPEARANCES_ID"
add_test "基础分析" "按球员分组统计总助攻数" "$APPEARANCES_ID"
add_test "基础分析" "各联赛的场均进球数" "$APPEARANCES_ID"

# B: 图表生成 (10)
for msg in \
  "画个柱状图展示各联赛的总进球数" \
  "用折线图按月展示进球趋势" \
  "做个饼图看各联赛出场次数占比" \
  "画个柱状图比较各联赛平均进球和平均助攻" \
  "柱状图展示红牌最多的前10个联赛" \
  "折线图按日期统计总进球趋势" \
  "饼图展示各联赛进球占比" \
  "柱状图比较各球员的红牌数" \
  "画个柱状图展示各联赛助攻总数" \
  "柱状图展示球员进球数排名前20"; do
  add_test "图表生成" "$msg" "$APPEARANCES_ID"
done

# C: 多文件关联 (8)
for msg in \
  "关联出场数据和球员信息统计各位置球员的总进球数" \
  "关联出场数据和球员信息分析不同位置球员的平均出场时间" \
  "关联出场数据和球员信息统计各国籍球员的进球数" \
  "关联出场数据和球员信息分析身高与进球数的关系" \
  "关联出场数据和球员信息统计左右脚球员的助攻差异" \
  "关联出场数据和球员信息对比前锋和中场球员的进球效率" \
  "关联出场数据和球员信息分析不同位置球员的犯规数据" \
  "关联出场数据和球员信息统计各位置球员的出场总时间"; do
  add_test "多文件关联" "$msg" "$APPEARANCES_ID,$PLAYER_ID"
done

# D: 多步分析 (7)
for msg in \
  "先统计各联赛的总进球数再找出进球最多的前5个联赛" \
  "先计算每个球员的平均进球数再按位置分组对比" \
  "先统计各月的总进球趋势再找出进球最多的月份" \
  "先统计不同联赛的场均进球再排序" \
  "先看各球员的助攻排名再分析前10名的出场时间" \
  "先找出红牌最多的球员再分析他的犯规规律" \
  "先统计各联赛的出场总时间再计算场均时间排名"; do
  add_test "多步分析" "$msg" "$APPEARANCES_ID"
done

# E: 复杂SQL/统计 (15)
for msg in \
  "筛选出场时间大于500分钟的球员统计他们的进球总数" \
  "统计进球数大于5的球员按联赛分组" \
  "找出出场时间超过1000分钟且红牌数为0的球员" \
  "按日期筛选2020年后的数据统计场均进球" \
  "统计单场比赛进球超过2个的比赛场次占比" \
  "统计进球数助攻数出场时间都高于平均的球员" \
  "统计进球数的分布情况均值最大最小标准差" \
  "统计出场时间的分布情况" \
  "统计助攻数的分布情况" \
  "描述统计所有数值列的统计量" \
  "给出minutes_played的百分位数分布" \
  "给出goals和assists的统计描述" \
  "统计各数值列的中位数和四分位距" \
  "计算goals的百分位数分布" \
  "统计各联赛场次总进球平均进球等汇总数据"; do
  add_test "复杂SQL" "$msg" "$APPEARANCES_ID"
done

# F: 模糊意图 (5)
for msg in "看看这些数据" "帮我分析一下" "有什么有趣的发现" "这个数据能看出什么" "随便看看有什么规律"; do
  add_test "模糊意图" "$msg" "$APPEARANCES_ID"
done

# G: 闲聊/问候 (5)
for msg in "你好" "谢谢" "再见" "你是谁" "你能做什么"; do
  add_test "闲聊问候" "$msg" ""
done

# H: 补充到78 (进阶分析+转会数据)
for msg in \
  "帮我看看转会数据哪些球员转会费最高" \
  "分析转会费和球员身价的差异" \
  "统计各联赛的转会总支出" \
  "画个柱状图展示各联赛转会支出排名" \
  "关联出场数据和球员信息分析左右脚球员差异"; do
  add_test "进阶分析" "$msg" "$APPEARANCES_ID,$PLAYER_ID"
done

for msg in \
  "统计各competition_id的进球数量" \
  "按球员分组统计总红牌数" \
  "各联赛出场时间占比分析" \
  "球员进球效率排名" \
  "各联赛场均助攻对比"; do
  add_test "基础分析" "$msg" "$APPEARANCES_ID"
done

TOTAL=${#TESTS[@]}
echo "共 $TOTAL 个测试用例"
echo ""

for ((i=0; i<TOTAL; i++)); do
  IFS='|' read -r cat msg fids <<< "${TESTS[$i]}"

  start_ts=$(date +%s)

  if [ -n "$fids" ]; then
    output=$(curl -s --max-time 180 -X POST "$BASE/agent/chat/stream" \
      --data-urlencode "message=$msg" \
      --data-urlencode "fileIds=$fids" 2>/dev/null)
  else
    output=$(curl -s --max-time 180 -X POST "$BASE/agent/chat/stream" \
      --data-urlencode "message=$msg" 2>/dev/null)
  fi
  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))

  # 解析结果
  has_complete=0; has_token=0; has_chart=0
  echo "$output" | grep -q "event: complete" && has_complete=1
  echo "$output" | grep -q '"tokenUsage"' && has_token=1
  echo "$output" | grep -q '"chartOption"' && has_chart=1

  # 错误检测 (兼容Windows: 不用 -i 则用 tr)
  error_type=""
  output_lc=$(echo "$output" | tr '[:upper:]' '[:lower:]')
  echo "$output_lc" | grep -q "查询语法错误" && error_type="syntax"
  echo "$output_lc" | grep -q "列名或表名不存在" && error_type="syntax"
  echo "$output_lc" | grep -q "语法错误" && error_type="syntax"
  echo "$output_lc" | grep -q "binder error" && error_type="syntax"
  echo "$output_lc" | grep -q "parser error" && error_type="syntax"
  echo "$output_lc" | grep -q "查询超时" && error_type="timeout"
  echo "$output_lc" | grep -q "系统异常" && error_type="system"
  echo "$output_lc" | grep -q "数据引擎异常" && error_type="system"
  echo "$output_lc" | grep -q "处理失败" && error_type="system"
  echo "$output_lc" | grep -q "图表构建失败" && error_type="system"
  echo "$output_lc" | grep -q "没有已加载的数据文件" && error_type="syntax"
  echo "$output_lc" | grep -q "文件加载失败" && error_type="system"
  echo "$output_lc" | grep -q "分析目标不明确" && error_type="syntax"

  if [ "$has_complete" -eq 1 ] && [ "$has_token" -eq 1 ] && [ -z "$error_type" ]; then
    success="true"
  else
    success="false"
  fi

  echo "${i}|${cat}|${msg:0:50}|${success}|${error_type:-none}|${has_token}|${has_chart}|${elapsed}" >> "$RESULTS_FILE"

  status_icon="✓"; [ "$success" = "false" ] && status_icon="✗"
  printf "  [%3d/%d] %s %-10s | %5ss | %s\n" $((i+1)) $TOTAL "$status_icon" "$cat" "$elapsed" "${msg:0:45}"
done

# ─── 4. 统计输出 ───
echo ""
echo "========================================"
echo "  CData Agent 工具调用成功率测试报告"
echo "========================================"

total=$(tail -n +2 "$RESULTS_FILE" | wc -l)
success_count=$(grep "|true|" "$RESULTS_FILE" | wc -l)
fail_count=$(grep "|false|" "$RESULTS_FILE" | wc -l)
# 用awk做除法(兼容Windows: 不用bc)
rate=$(awk "BEGIN {printf \"%.1f\", $success_count * 100 / $total}" 2>/dev/null || echo "N/A")

echo "  总测试数: $total"
echo "  成功: $success_count"
echo "  失败: $fail_count"
echo "  成功率: ${rate}%"
echo ""

echo "  --- 场景成功率 ---"
for cat_name in "基础分析" "图表生成" "多文件关联" "多步分析" "复杂SQL" "模糊意图" "闲聊问候" "进阶分析"; do
  cat_total=$(grep "|${cat_name}|" "$RESULTS_FILE" | wc -l)
  [ "$cat_total" -eq 0 ] && continue
  cat_ok=$(grep "|${cat_name}|" "$RESULTS_FILE" | grep "|true|" | wc -l)
  cat_rate=$(awk "BEGIN {printf \"%.1f\", $cat_ok * 100 / $cat_total}" 2>/dev/null || echo "N/A")
  echo "    ${cat_name}: ${cat_rate}% ($cat_ok/$cat_total)"
done

echo ""
echo "  --- 错误分布 ---"
# 提取错误类型列 (第5列, 以|分隔)
awk -F'|' 'NR>1 && $5!="none" {e[$5]++} END {for(k in e) print e[k], k}' "$RESULTS_FILE" | sort -rn | while read count type; do
  echo "    $type: $count 次"
done
[ "$(awk -F'|' 'NR>1 && $5!="none" {print}' "$RESULTS_FILE" | wc -l)" -eq 0 ] && echo "    (无错误)"

echo ""
echo "  --- 失败详情 ---"
grep "|false|" "$RESULTS_FILE" | head -20 | while IFS='|' read -r idx cat msg success err token chart elapsed; do
  echo "    #${idx} [${cat}] ${msg:0:45} | ${err} | ${elapsed}s"
done
[ "$(grep '|false|' "$RESULTS_FILE" | wc -l)" -eq 0 ] && echo "    (无失败)"

echo ""
echo "  --- 工具调用日志 (从后端日志中提取) ---"
echo "  命令: grep 'ToolStat' logs/app.log"
echo "  按工具: grep 'ToolStat' logs/app.log | grep -o 'tool=[^,]*' | sort | uniq -c | sort -rn"
echo "  按失败类型: grep 'ToolStat.*FAILED' logs/app.log | grep -o 'errType=[^,]*' | sort | uniq -c | sort -rn"
echo "  平均耗时: grep 'ToolStat.*SUCCESS' logs/app.log | grep -o 'elapsed=[0-9]*' | grep -o '[0-9]*' | awk '{s+=\$1; c++} END {printf \"%.0fms\\n\", s/c}'"
echo "========================================"
echo "详细结果文件: $RESULTS_FILE"

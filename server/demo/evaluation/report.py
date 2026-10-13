"""Render stored synthetic evaluations without new model calls."""
import argparse
import json
from pathlib import Path

GROUP = {"sales": "推销", "colleague": "公司同事",
         "friend": "老朋友", "family": "家人"}
INTENT = {"normal": "普通事务", "marketing": "推销",
          "suspicious": "疑似风险", "unknown": "无法判断"}
ACTION = {"SIMULATED_ALLOW_5M": "模拟允许五分钟内再拨",
          "SIMULATED_BLOCKLIST": "模拟加入黑名单",
          "SIMULATED_REVIEW": "模拟人工复核",
          "ASK_ONE_MORE": "追问"}


def read_rows(path):
    return {row["id"]: row for row in
            (json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())}


def line(value):
    return str(value).replace("\r", " ").replace("\n", " ").replace("|", "\\|")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--targeted", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8-sig"))
    baseline, targeted = read_rows(args.baseline), read_rows(args.targeted)
    base_summary = json.loads(args.baseline.with_suffix(".summary.json").read_text(encoding="utf-8"))
    target_summary = json.loads(args.targeted.with_suffix(".summary.json").read_text(encoding="utf-8"))
    if len(cases) != 30 or len(baseline) != 30 or len(targeted) != 7:
        raise ValueError("Unexpected evidence counts")
    out = [
        "# CallGuard AI 文字模拟来电评估",
        "",
        "全部为虚构文字；没有真实电话号码、录音、Phone Link 音频或手机名单变更。模型只看到文字内容，自称关系不等于身份已验证。",
        "",
        "## 评估结论",
        "",
        f"- 优化前基线：30 份，{base_summary['model_calls_attempted']} 次模型请求；来意 {base_summary['intent_correct']}/30，模拟动作 {base_summary['action_correct']}/30；误触发模拟黑名单 {base_summary['false_simulated_blocks']} 次，调用错误 {base_summary['errors']} 次。",
        f"- 优化后定向复测：原先失败的 5 份加推销、家人代表各 1 份，共 7 份、{target_summary['model_calls_attempted']} 次请求；来意 {target_summary['intent_correct']}/7、自称关系 {target_summary['relationship_correct']}/7、模拟动作 {target_summary['action_correct']}/7。",
        "- 新版未对全部 30 份重新跑付费模型，不能写成新版 30/30。两组样本来自同一份脚本，定向复测不是独立泛化验证。",
        "",
        "| 群组 | 数量 | 优化前来意符合预期 |",
        "| --- | ---: | ---: |",
    ]
    for group in ("sales", "colleague", "friend", "family"):
        item = base_summary["by_group"][group]
        out.append(f"| {GROUP[group]} | {item['total']} | {item['intent_correct']} |")
    out.extend([
        "",
        "## 根据失败样本所作调整",
        "",
        "原模型把来意判断的置信度与自称身份的可信程度、事务细节完整度混在一起：同事未报文档名/会议室，朋友未证明身份，就把明确的普通来电降为低置信度或 unknown。新版将 intent 与 claimed_relationship 分开，明确 identity_verified=false；只有来意类别不清时才追问。模型不能直接选择动作，后端仍以明确阈值映射模拟结果；自称商家的 normal 标签不得直接获得模拟放行。",
        "",
        "## 优化前：30 份完整模拟对话",
        "",
    ])
    for case in cases:
        row = baseline[case["id"]]
        out.extend([
            f"### {case['id']} · {GROUP[case['group']]}",
            "",
            f"预期来意：{INTENT[case['expected_intent']]}；实际：{INTENT[row['responses'][-1]['intent']]}（{row['responses'][-1]['confidence']:.2f}）；动作：{ACTION[row['responses'][-1]['action']]}；来意/动作符合预期：{'是' if row['intent_pass'] and row['action_pass'] else '否'}。",
            "",
            f"- 来电者：{line(case['opening'])}",
            f"- AI：{line(row['responses'][0]['reply'])}",
        ])
        if row["clarification_used"]:
            out.extend([
                f"- 来电者：{line(case['clarification'])}",
                f"- AI：{line(row['responses'][1]['reply'])}",
            ])
        out.append("")
    out.extend(["## 优化后：7 份定向复测", ""])
    for case in cases:
        if case["id"] not in targeted:
            continue
        row = targeted[case["id"]]
        last = row["responses"][-1]
        out.extend([
            f"### {case['id']} · {GROUP[case['group']]}",
            "",
            f"来意：{INTENT[last['intent']]}（{last['confidence']:.2f}）；自称关系：{last['claimed_relationship']}，未核验；动作：{ACTION[last['action']]}。",
            "",
            f"- 来电者：{line(case['opening'])}",
            f"- AI：{line(row['responses'][0]['reply'])}",
        ])
        if row["clarification_used"]:
            out.extend([f"- 来电者：{line(case['clarification'])}",
                        f"- AI：{line(row['responses'][1]['reply'])}"])
        out.append("")
    out.extend([
        "## 证据边界",
        "",
        "两组结果只表明此固定、虚构文本集上的模型行为；不能推断真实诈骗识别准确率，也不能证明音频接入、自动接听、ASR/TTS、真实放行或黑名单已实现。网页可载入同一批 30 份脚本供人工继续体验；每次点击发送都会调用模型，可能计费。",
        "",
    ])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(out), encoding="utf-8")


if __name__ == "__main__":
    main()

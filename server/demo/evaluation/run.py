"""Bounded paid evaluation: synthetic text only, no automatic retries."""
import argparse
import asyncio
import json
import time
from collections import Counter
from pathlib import Path

from server.app.models.factory import create_model
from server.demo.logic import simulate
from server.demo.schema import SimulateRequest
from server.run_demo import load_project_env

DEFAULT_CASES = Path(__file__).parent / "cases.json"


async def run_case(case, model):
    started = time.perf_counter()
    turns = [{"role": "caller", "text": case["opening"]}]
    responses = []
    try:
        first = await simulate(SimulateRequest(turns=turns), model)
        responses.append(first.model_dump())
        if first.status == "CONTINUE":
            turns.extend([{"role": "assistant", "text": first.reply},
                          {"role": "caller", "text": case["clarification"]}])
            second = await simulate(SimulateRequest(turns=turns), model)
            responses.append(second.model_dump())
        final = responses[-1]
        expected_action = ("SIMULATED_REVIEW" if case["group"] == "sales"
                           else "SIMULATED_ALLOW_5M")
        expected_relationship = {"sales": "commercial", "colleague": "colleague",
                                 "friend": "friend", "family": "family"}[case["group"]]
        return {
            "id": case["id"], "group": case["group"],
            "synthetic": True, "opening": case["opening"],
            "clarification_used": len(responses) == 2,
            "clarification": case["clarification"] if len(responses) == 2 else None,
            "expected_intent": case["expected_intent"],
            "expected_action": expected_action,
            "expected_relationship": expected_relationship,
            "responses": responses,
            "intent_pass": final["intent"] == case["expected_intent"],
            "relationship_pass": final["claimed_relationship"] == expected_relationship,
            "action_pass": final["action"] == expected_action,
            "false_simulated_block": final["action"] == "SIMULATED_BLOCKLIST",
            "model_calls": len(responses),
            "elapsed_ms": round((time.perf_counter() - started) * 1000)
        }
    except Exception as exc:
        return {
            "id": case["id"], "group": case["group"], "synthetic": True,
            "opening": case["opening"], "expected_intent": case["expected_intent"],
            "responses": responses, "error_type": type(exc).__name__,
            "intent_pass": False, "relationship_pass": False, "action_pass": False,
            "false_simulated_block": False,
            "model_calls": len(responses) + 1,
            "elapsed_ms": round((time.perf_counter() - started) * 1000)
        }


async def evaluate(cases, model, output, concurrency):
    semaphore = asyncio.Semaphore(concurrency)
    file_lock = asyncio.Lock()
    results = []

    async def execute(case):
        async with semaphore:
            result = await run_case(case, model)
        async with file_lock:
            with output.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(result, ensure_ascii=False) + "\n")
                stream.flush()
            results.append(result)
            print(case["id"] + ": " +
                  ("ERROR " + result["error_type"] if "error_type" in result
                   else result["responses"][-1]["intent"] + " / " +
                   result["responses"][-1]["action"]), flush=True)

    await asyncio.gather(*(execute(case) for case in cases))
    group_totals = Counter(item["group"] for item in results)
    group_pass = Counter(item["group"] for item in results if item["intent_pass"])
    summary = {
        "synthetic": True, "total": len(results),
        "model_calls_attempted": sum(item["model_calls"] for item in results),
        "intent_correct": sum(item["intent_pass"] for item in results),
        "relationship_correct": sum(item["relationship_pass"] for item in results),
        "action_correct": sum(item["action_pass"] for item in results),
        "false_simulated_blocks": sum(item["false_simulated_block"] for item in results),
        "errors": sum("error_type" in item for item in results),
        "by_group": {group: {"total": count, "intent_correct": group_pass[group]}
                     for group, count in sorted(group_totals.items())},
        "failed_ids": sorted(item["id"] for item in results
                             if not item["intent_pass"] or not item["action_pass"] or not item["relationship_pass"]),
        "raw_results": str(output)
    }
    summary_path = output.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                            encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--only", nargs="*", default=None)
    parser.add_argument("--concurrency", type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.concurrency <= 3:
        parser.error("concurrency must be 1..3")
    if args.output.exists() or args.output.with_suffix(".summary.json").exists():
        parser.error("output exists; no automatic repeat or overwrite")
    cases = json.loads(args.cases.read_text(encoding="utf-8-sig"))
    if len(cases) != 30 or Counter(case["group"] for case in cases) != {
        "sales": 10, "colleague": 5, "friend": 5, "family": 10
    }:
        parser.error("Expected 30 cases in the requested 10/5/5/10 distribution")
    if args.only is not None:
        selected = set(args.only)
        cases = [case for case in cases if case["id"] in selected]
        if len(cases) != len(selected):
            parser.error("Unknown or repeated case ID")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    load_project_env()
    model = create_model()
    summary = asyncio.run(evaluate(cases, model, args.output, args.concurrency))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

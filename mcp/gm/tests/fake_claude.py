#!/usr/bin/env python3
"""Заглушка claude для теста диспетчера: голос (stream-json) «отвечает» записью reply в журнал, исполнитель
закрывает свою задачу. Сеть и модели не нужны; argv — как у настоящего claude, лишнее не разбирается."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import conf  # noqa: E402
import store as storemod  # noqa: E402

cfg = conf.load()
store = storemod.Store(cfg["paths"]["db"])
role = os.environ["GM_ROLE"]

if "--input-format" in sys.argv:
    for line in sys.stdin:
        text = json.loads(line)["message"]["content"]
        turn = store.get("voice.turn")
        if "молчи" not in text:
            store.log("voice", "reply", turn["player"], text="ответ: " + text[-60:], ms=5)
        if "поставь задачу" in text:
            store.task_add(turn["player"], "построить домик", channel=turn["channel"], minutes=1)
        print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "total_cost_usd": 0.001,
                          "usage": {"input_tokens": 10}}), flush=True)
elif "--json-schema" in sys.argv:  # проверочный набор или рецензент
    prompt = sys.stdin.read()
    schema = sys.argv[sys.argv.index("--json-schema") + 1]
    if "verdict" in schema:
        out = {"verdict": "block" if "запрещено" in prompt else "clean", "reasons": []}
    else:
        out = {"cases": [{"id": line[4:].strip(), "action": "answer", "recipe": None, "reply": "ок"}
                         for line in prompt.splitlines() if line.startswith("id: ")]}
    print(json.dumps({"type": "result", "result": "", "structured_output": out, "total_cost_usd": 0.002}))
elif role == "teacher":
    sys.stdin.read()
    os.makedirs("skill/knowledge", exist_ok=True)
    with open("skill/knowledge/places.md", "w", encoding="utf-8") as f:
        f.write("# Места\n\n- Спавн: 0 64 0.\n")
    with open("evals/learned.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps({"id": "learned-spawn", "text": "где спавн?", "expect": {"action": ["answer"]}},
                           ensure_ascii=False) + "\n")
    print(json.dumps({"type": "result", "result": "разобрал опыт\nДобавил место спавна", "total_cost_usd": 0.05}))
elif role == "worker":
    sys.stdin.read()
    task = int(os.environ["GM_TASK"])
    store.finish(task, "done", "домик стоит у 10 64 10", {"method": "blocks", "result": {"ok": True}})
    print(json.dumps({"type": "result", "result": "сделано", "total_cost_usd": 0.01, "num_turns": 3}))

#!/usr/bin/env python3
"""Хук PreToolUse сессий ведущего: охрана (guard.py), которая отказывает и при собственной ошибке.

Claude Code блокирует вызов только по отказу в JSON (код 0) или по коду 2; любой другой код и срок хука вызов
пропускают. Поэтому всё — даже импорт охраны — под try: что бы ни сломалось, ответ — код 2 с причиной в stderr.
"""
import sys

try:
    import guard

    code = guard.main()
except BaseException as e:  # noqa: BLE001 — охрана не знает, что это, — значит нельзя
    print(f"охрана не смогла проверить ({type(e).__name__}: {e}) — действие не выполнено", file=sys.stderr)
    sys.exit(2)
sys.exit(code)

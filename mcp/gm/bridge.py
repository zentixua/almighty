"""Мост Almighty для служб ведущего без зависимостей: POST /rpc с токеном — то же, что rpc в almighty.py."""
import json
import urllib.error
import urllib.request


class BridgeError(Exception):
    pass


class Bridge:
    def __init__(self, url, token_file):
        self.url = url.rstrip("/") + "/rpc"
        self.token_file = token_file
        # мост — на петле: прокси окружения ему не нужен
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, params=None, timeout=70):
        """Вызов метода мода; ошибка мода, сети или токена — BridgeError с текстом. Токен читается на каждый
        вызов: сервер создал новый (первый запуск, перенос) — служба берёт его без перезапуска."""
        body = json.dumps({"method": method, "params": {k: v for k, v in (params or {}).items() if v is not None}})
        try:
            with open(self.token_file, encoding="utf-8") as f:
                token = f.read().strip()
        except OSError as e:
            raise BridgeError(f"токен моста {self.token_file} не прочитан: {e}") from e
        request = urllib.request.Request(self.url, data=body.encode(), method="POST", headers={
            "Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with self._opener.open(request, timeout=timeout) as r:
                data = json.loads(r.read())
        except urllib.error.HTTPError as e:
            try:
                data = json.loads(e.read())
            except ValueError:
                raise BridgeError(f"мост ответил {e.code}") from e
        except (OSError, ValueError) as e:
            raise BridgeError(f"мост {self.url} недоступен: {e}") from e
        if not data.get("ok"):
            err = data.get("error") or {}
            raise BridgeError(f"{err.get('code', '?')}: {err.get('message', '')}")
        return data["result"]


def of(cfg, section="bridge"):
    """Мост из настроек: bridge — игра, test_bridge — тестовый сервер (None, если его нет)."""
    s = cfg[section]
    if not s.get("url") or not s.get("token_file"):
        return None
    return Bridge(s["url"], s["token_file"])

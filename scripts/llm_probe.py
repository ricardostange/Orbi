#!/usr/bin/env python3
"""Inspect the packaged agent or send one chat/completions request; never dispatch tools."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "app/src/main/assets/agent"


def load_config(path):
    config = json.loads(Path(path).read_text(encoding="utf-8"))
    url = urllib.parse.urlsplit(config["endpoint"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("Endpoint deve ser HTTPS, sem credenciais, query ou fragmento.")
    if not config["provider"].strip() or not config["model"].strip():
        raise ValueError("Provedor/modelo obrigatório.")
    if not 0 <= config["temperature"] <= 2 or not 1 <= config["max_tokens"] <= 32768:
        raise ValueError("Parâmetros de geração inválidos.")
    for field, maximum in (("connect_timeout_seconds", 300), ("read_timeout_seconds", 300), ("call_timeout_seconds", 600)):
        if not 1 <= config[field] <= maximum:
            raise ValueError("Timeout inválido.")
    if not set(config.get("extra_body", {})) <= {"thinking", "reasoning_effort", "top_p", "frequency_penalty", "presence_penalty"}:
        raise ValueError("Parâmetro extra não permitido.")
    return config


def spotify_schema():
    # Use the exact versioned Kotlin schema, including all parameter validation hints.
    source = (ROOT / "app/src/main/java/br/com/assistente/teste/integrations/spotify/SpotifyIntegration.kt").read_text(encoding="utf-8")
    match = re.search(r'private const val SCHEMA = """(.*?)"""', source, re.S)
    if not match:
        raise ValueError("Não foi possível localizar o schema Spotify.")
    return json.loads(match.group(1))


def build_request(config, message, tools, prompts=None):
    names = [tool["function"]["name"] for tool in tools]
    system = (AGENT / "prompts/system.md").read_text(encoding="utf-8").strip()
    availability = (AGENT / ("prompts/connected.md" if tools else "prompts/disconnected.md")).read_text(encoding="utf-8").strip()
    availability = availability.replace("{{tools}}", ", ".join(names))
    if "spotify" in names:
        availability += "\n" + (AGENT / "prompts/spotify.md").read_text(encoding="utf-8").strip()
    if prompts:
        system = "\n\n".join(Path(p).read_text(encoding="utf-8").strip() for p in prompts)
    body = dict(config.get("extra_body", {}))
    body.update(model=config["model"], stream=False, temperature=config["temperature"], max_tokens=config["max_tokens"],
                messages=[{"role": "system", "content": system + " " + availability}, {"role": "user", "content": message}])
    if tools:
        body.update(tools=tools, tool_choice="auto")
    return body


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--message", required=True)
    parser.add_argument("--config", type=Path, default=AGENT / "provider.json")
    parser.add_argument("--prompt", action="append", type=Path, help="Substitui system.md; pode ser repetido")
    parser.add_argument("--spotify-tools", action="store_true", help="Oferece o schema; não conecta conta nem executa ações")
    parser.add_argument("--send", action="store_true", help="Envia uma requisição real (cobrança do provedor)")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        body = build_request(config, args.message, [spotify_schema()] if args.spotify_tools else [], args.prompt)
        key = os.environ.get("ORBI_LLM_API_KEY", "")
        if args.send and not key:
            key = getpass.getpass("Chave de API (não será exibida): ").strip()
        def display(value):
            text = json.dumps(value, ensure_ascii=False, indent=2)
            print(text.replace(key, "[REDACTED]") if key else text)
        display({"endpoint": config["endpoint"], "request": body})
        if not args.send:
            print("Inspeção local. Use --send para enviar. Nenhuma ferramenta será executada.")
            return 0
        if not key or any(c.isspace() for c in key):
            raise ValueError("Chave inválida.")
        request = urllib.request.Request(config["endpoint"], data=json.dumps(body).encode(), method="POST",
                                         headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        # urllib exposes a socket timeout; the Android client additionally enforces its total call deadline.
        opener = urllib.request.build_opener(NoRedirect)
        with opener.open(request, timeout=min(config["read_timeout_seconds"], config["call_timeout_seconds"])) as response:
            result = json.load(response)
        message = result["choices"][0]["message"]
        display({"response": message, "tool_calls_proposed_only": message.get("tool_calls", [])})
        return 0
    except urllib.error.HTTPError as exc:
        print(f"HTTP {exc.code}. Confira autenticação, saldo, limites e compatibilidade do endpoint.", file=sys.stderr)
    except (urllib.error.URLError, TimeoutError, OSError):
        print("Falha de conexão ou timeout. Nenhuma ferramenta foi executada.", file=sys.stderr)
    except (ValueError, KeyError, IndexError, TypeError):
        print("Configuração, schema ou resposta incompatível. Confira os arquivos e o formato chat/completions.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

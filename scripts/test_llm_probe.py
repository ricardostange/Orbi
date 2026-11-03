import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("llm_probe", Path(__file__).with_name("llm_probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.config = probe.load_config(probe.AGENT / "provider.json")

    def test_disconnected_prompt_and_no_tools(self):
        body = probe.build_request(self.config, "Olá", [])
        self.assertNotIn("tools", body)
        self.assertIn("Nenhuma ferramenta disponível", body["messages"][0]["content"])
        self.assertEqual("Olá", body["messages"][1]["content"])
        self.assertEqual(self.config["extra_body"]["thinking"], body["thinking"])

    def test_connected_prompt_uses_actual_kotlin_schema(self):
        schema = probe.spotify_schema()
        body = probe.build_request(self.config, "Pause", [schema])
        self.assertEqual("spotify", body["tools"][0]["function"]["name"])
        self.assertIn("pause", schema["function"]["parameters"]["properties"]["action"]["enum"])
        self.assertIn("Spotify: consulte", body["messages"][0]["content"])
        self.assertNotIn("{{tools}}", body["messages"][0]["content"])

    def test_rejects_http_and_body_override(self):
        for patch in ({"endpoint": "http://example.com"}, {"extra_body": {"messages": []}}):
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "config.json"
                path.write_text(json.dumps(dict(self.config, **patch)))
                with self.assertRaises(ValueError):
                    probe.load_config(path)


if __name__ == "__main__":
    unittest.main()

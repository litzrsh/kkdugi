import json
import unittest
from unittest.mock import patch

import ollama_implements as target


class ProfileTest(unittest.TestCase):
    def test_java_default_is_unchanged(self):
        for role in target.ROLES:
            self.assertEqual(target.system_prompt(role, "java"), target.ROLES[role]["system"])

    def test_go_has_no_java_base_class_requirements(self):
        for role in target.ROLES:
            prompt = target.system_prompt(role, "go")
            self.assertIn("Go", prompt)
            self.assertNotIn("extend BaseParams", prompt)
            self.assertNotIn("senior Java developer", prompt)

    def test_request_selects_profile_without_changing_model(self):
        with patch.object(target.urllib.request, "urlopen") as request:
            request.return_value.__enter__.return_value.read.return_value = b'{"response":"package config"}'
            self.assertEqual(target.call_ollama("coder", "types", "reference", "go"), "package config")
            payload = json.loads(request.call_args.args[0].data)
            self.assertEqual(payload["model"], target.ROLES["coder"]["model"])
            self.assertEqual(payload["system"], target.system_prompt("coder", "go"))
            self.assertEqual(payload["keep_alive"], "30m")
            self.assertFalse(payload["stream"])
            self.assertNotIn("think", payload)

    def test_explicit_thinking_option(self):
        with patch.object(target.urllib.request, "urlopen") as request:
            request.return_value.__enter__.return_value.read.return_value = b'{"response":"No findings."}'
            target.call_ollama("reviewer", "review", "code", "go", False)
            self.assertFalse(json.loads(request.call_args.args[0].data)["think"])

    def test_invalid_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            target.system_prompt("coder", "unknown")

    def test_empty_model_response_is_not_a_successful_review(self):
        with patch.object(target.urllib.request, "urlopen") as request:
            request.return_value.__enter__.return_value.read.return_value = b'{"response":"", "thinking":"unfinished"}'
            with self.assertRaisesRegex(SystemExit, "empty response"):
                target.call_ollama("reviewer", "review", "code", "go")


if __name__ == "__main__":
    unittest.main()

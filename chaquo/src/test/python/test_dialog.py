import importlib.util
import json
import pathlib
import sys
import types
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

_PATH = pathlib.Path(__file__).parents[2] / 'main' / 'python' / '_tv_dialog.py'
_SPEC = importlib.util.spec_from_file_location('_dialog_under_test', _PATH)
MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(MODULE)

class JsonObject:
    def __init__(self, value): self.value = json.loads(value)
    def __str__(self): return json.dumps(self.value)

class Native:
    def __init__(self): self.closed = False; self.callback_result = None
    def show(self, options, callback):
        event = JsonObject('{"button":"ok","values":{"code":"1234"}}')
        if callback: self.callback_result = callback.onAction(event)
        return event
    def close(self): self.closed = True

class DialogTests(unittest.TestCase):
    def setUp(self):
        java = types.SimpleNamespace(jclass=lambda name: JsonObject if name == 'org.json.JSONObject' else object,
                                     dynamic_proxy=lambda interface: object)
        self.modules = patch.dict(sys.modules, {'java': java})
        self.modules.start()
    def tearDown(self): self.modules.stop()
    def test_options_and_result_round_trip(self):
        native = Native(); adapter = MODULE.DialogAdapter(native)
        self.assertEqual('1234', adapter.show({'title': '验证码'})['values']['code'])
    def test_callback_receives_dict_and_returns_patch(self):
        native = Native(); adapter = MODULE.DialogAdapter(native)
        adapter.show({}, lambda event: {'close': False, 'values': event['values']})
        self.assertEqual({'close': False, 'values': {'code': '1234'}}, native.callback_result.value)
    def test_invalid_options_callbacks_and_results_are_rejected(self):
        adapter = MODULE.DialogAdapter(Native())
        with self.assertRaises(TypeError): adapter.show([])
        with self.assertRaises(TypeError): adapter.show({}, 'not callable')
        with self.assertRaises(TypeError): adapter.show({}, lambda event: [])
        with self.assertRaises(ValueError): adapter.show({'value': float('nan')})
    def test_close_is_idempotent_and_owner_local(self):
        first, second = Native(), Native()
        a, b = MODULE.DialogAdapter(first), MODULE.DialogAdapter(second)
        a.close(); a.close()
        self.assertTrue(first.closed)
        self.assertFalse(second.closed)
        with self.assertRaises(RuntimeError): a.show({})
        self.assertIsNotNone(b.show({}))

if __name__ == '__main__': unittest.main()

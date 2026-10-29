"""Reproduce remaining NetMon 1.1 defects without calling Windows Firewall.

Usage: python review_checks.py /path/to/unpacked/netmon [--pwsh /path/to/pwsh]
All firewall operations use test doubles. PASS means the described defect was
reproduced, not that the application is correct.
"""
import argparse
import importlib.util
import json
import logging
import os
from pathlib import Path
import shutil
import sys
import tempfile
from unittest.mock import patch

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path, help='Directory containing model.py and firewall.py')
parser.add_argument('--pwsh', default=os.environ.get('NETMON_PWSH') or shutil.which('pwsh'))
args = parser.parse_args()
sys.path.insert(0, str(args.source.resolve()))
import firewall
from model import Model

firewall.log.disabled = True
results = {}


def traffic(m, timestamp, byte_count):
    m.on_traffic(100, 'TCP', True, byte_count, '1.1.1.1', 443, '10.0.0.1', 50000, timestamp)


# PID changed between process enumeration and querying process details.
m = Model()
m.resolve_names = False
m.sync_processes({100: ('old.exe', 1)}, now=100)
uid = m.by_pid[100].uid
traffic(m, 100.1, 10)
m.process_pending()
m.fill_details(uid, {'path': r'C:\new.exe', 'started': 100.5}, now=100.6)
records = [(p.uid, p.name, p.started, p.sent) for p in m.procs.values()]
assert records == [(uid, 'new.exe', 100.5, 10)], records
results['rapid_pid_reuse'] = {'records': records, 'defect_reproduced': True}


# Same tuple is reused, but one event belongs to a previous process instance.
m = Model()
m.resolve_names = False
m.on_process_start(100, 1, r'C:\old.exe')
m.on_process_stop(100, 3)
m.on_process_start(100, 10, r'C:\new.exe')
traffic(m, 2.5, 7)
traffic(m, 11, 9)
m.process_pending()
counts = {p.name: p.sent for p in m.procs.values()}
assert counts == {'old.exe': 16, 'new.exe': 0}, counts
results['late_packet_same_tuple'] = {'expected': {'old.exe': 7, 'new.exe': 9},
                                   'actual': counts, 'defect_reproduced': True}


# A rule disappears externally while the UI still contains the selected entry.
app = r'C:\Apps\demo.exe'
backend = firewall.DemoFirewall(delay=0)
manager = firewall.FirewallManager(backend)
try:
    manager.block(app).result()
    manager.set_enabled(manager.entries, False).result()
    manager.take_messages()
    selected = list(manager.entries)
    backend.remove([r.id for r in backend.rules.values() if r.direction == 'out'])
    manager.set_enabled(selected, True).result()
    actual = [(r.direction, r.enabled) for r in backend.rules.values()]
    messages = manager.take_messages()
    assert actual == [('in', True)] and messages[0][0] == 'info', (actual, messages)
    results['resume_missing_outbound_rule'] = {'actual_rules': actual, 'messages': messages,
                                             'status': manager.status(app),
                                             'defect_reproduced': True}
finally:
    manager.shutdown()


if args.pwsh:
    spec = importlib.util.spec_from_file_location('netmon_test_firewall',
                                                 args.source / 'tests/test_firewall.py')
    stock_tests = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stock_tests)
    declaration = 'function Get-NetFirewallRule { [CmdletBinding()] param([string]$Group, [string]$Name)'
    assert declaration in stock_tests.FAKE_CMDLETS
    prelude = stock_tests.FAKE_CMDLETS.replace(declaration, declaration + '''
    if ($env:NETMON_REVIEW_FAIL_GET) {
        Write-Error -Category PermissionDenied 'Review: access denied reading rules'
        return
    }
''', 1)
    with tempfile.TemporaryDirectory() as temp:
        overrides = {'FAKE_FW_STORE': str(Path(temp) / 'rules.json'),
                     'FAKE_FW_DENY_NEW': '', 'FAKE_FW_DENY_SET': '',
                     'FAKE_FW_DENY_REMOVE': '', 'NETMON_REVIEW_FAIL_GET': ''}
        with patch.dict(os.environ, overrides):
            backend = firewall.WindowsFirewall(executable=args.pwsh, prelude=prelude)
            manager = firewall.FirewallManager(backend)
            try:
                manager.block(app).result()
                manager.take_messages()
                selected = list(manager.entries)
                assert len(backend.state()[0]) == 2
                os.environ['NETMON_REVIEW_FAIL_GET'] = '1'
                manager.unblock(selected).result()
                messages = manager.take_messages()
                cached = {'state_known': manager.state_known, 'entries': len(manager.entries),
                          'error': manager.error}
                os.environ['NETMON_REVIEW_FAIL_GET'] = ''
                actual = len(backend.state()[0])
                assert actual == 2 and cached['state_known'] and cached['entries'] == 0
                assert messages[0][0] == 'info', messages
                results['suppressed_read_failure'] = {'actual_rules_remaining': actual,
                                                      'cached': cached, 'messages': messages,
                                                      'defect_reproduced': True}
            finally:
                manager.shutdown()
else:
    results['suppressed_read_failure'] = {'skipped': 'PowerShell not supplied/found'}

print(json.dumps(results, ensure_ascii=False, indent=2))

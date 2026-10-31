"""Two remaining NetMon 1.2 process-attribution defects; no real network/firewall calls.

Usage: python NETMON_REVIEW_V1_2_CHECKS.py /path/to/unpacked/netmon
defect_reproduced=True means the original defect is present, not a passing fix.
"""
import argparse
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path, help='Directory containing model.py')
args = parser.parse_args()
sys.path.insert(0, str(args.source.resolve()))
from model import Model


def traffic(model, timestamp, size):
    model.on_traffic(100, 'TCP', True, size,
                     '1.1.1.1', 443, '10.0.0.1', 50000, timestamp)


report = {}
model = Model()
model.resolve_names = False
model.on_process_start(100, 100.0, r'C:\Old\app.exe')
traffic(model, 100.1, 10)
model.process_pending()
uid = model.by_pid[100].uid
# PID 100 already belongs to a new process, but its stop/start events have not
# been processed yet. The process details query returns the new executable.
model.fill_details(uid, {'path': r'C:\Other\app.exe', 'started': 100.4}, now=100.5)
actual = [(p.path, p.started, p.sent) for p in model.procs.values()]
assert actual == [(r'C:\Other\app.exe', 100.4, 10)], actual
report['different_paths_same_basename'] = {
    'actual': actual,
    'expected': 'Old executable retains its 10 bytes; new executable has a separate record',
    'defect_reproduced': True,
}

for order in ([(2.9, 7), (3.2, 9)], [(3.2, 9), (2.9, 7)]):
    model = Model()
    model.resolve_names = False
    model.on_process_start(100, 1.0, r'C:\old.exe')
    model.on_process_stop(100, 3.0)
    model.on_process_start(100, 3.1, r'C:\new.exe')
    for timestamp, size in order:
        traffic(model, timestamp, size)
    model.process_pending()
    actual = {p.name: p.sent for p in model.procs.values()}
    assert actual == {'old.exe': 0, 'new.exe': 16}, actual
report['close_lifetimes_delayed_packet'] = {
    'expected': {'old.exe': 7, 'new.exe': 9},
    'actual': actual,
    'both_delivery_orders_reproduced': True,
    'defect_reproduced': True,
}

print(json.dumps(report, ensure_ascii=False, indent=2))

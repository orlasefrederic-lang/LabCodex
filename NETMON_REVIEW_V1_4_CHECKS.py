#!/usr/bin/env python3
"""Netmon 1.4 review checks; assertions describe the correct behavior.

Usage: python NETMON_REVIEW_V1_4_CHECKS.py path/to/extracted/netmon
No network or Windows Firewall access. History test uses a temporary SQLite DB.
"""
import argparse
from pathlib import Path
import sys
import tempfile
import unittest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path)
args = parser.parse_args()
source = args.source.resolve()
if not (source / 'model.py').is_file():
    parser.error('source must contain model.py')
sys.path.insert(0, str(source))
from model import Model
from storage import Storage


def fresh():
    m = Model()
    m.resolve_names = False
    return m


def packet(m, ts, size):
    m.on_traffic(100, 'TCP', True, size,
                 '1.1.1.1', 443, '10.0.0.1', 50000, ts)


class Netmon14Review(unittest.TestCase):
    def test_v14_1_late_other_exe_start_does_not_duplicate_current(self):
        m = fresh()
        path = r'C:\New\app.exe'
        m.sync_processes({100: ('app.exe', 1)}, now=10.02)
        m.fill_details(m.by_pid[100].uid,
                       {'path': path, 'started': 10.01}, now=10.02)
        packet(m, 10.025, 10)
        m.process_pending()
        m.on_process_start(100, 10.0, r'C:\Old\app.exe')
        m.on_process_start(100, 10.015, path)
        packet(m, 10.03, 9)
        m.process_pending()
        current_exe = [p for p in m.procs.values() if p.path == path]
        actual = [(p.started, p.ended, p.sent) for p in current_exe]
        self.assertEqual(actual, [(10.01, None, 19)])
        self.assertIs(m.by_pid[100], current_exe[0])

    def test_v14_2_history_does_not_merge_other_full_path(self):
        with tempfile.TemporaryDirectory(prefix='netmon-review-') as folder:
            st = Storage(str(Path(folder) / 'history.sqlite3'))
            try:
                before = fresh()
                before.sync_processes({100: ('app.exe', 1)}, now=100.1)
                before.fill_details(before.by_pid[100].uid,
                                    {'path': r'C:\Old\app.exe', 'started': 100.0},
                                    now=100.1)
                packet(before, 100.15, 10)
                before.process_pending()
                st.save(*before.collect_dirty())
                # Old exits and its PID is reused while the monitor is closed.
                after = fresh()
                after.load_history(st.load())
                after.sync_processes({100: ('app.exe', 1)}, now=101.0)
                after.fill_details(after.by_pid[100].uid,
                                   {'path': r'C:\Other\app.exe', 'started': 100.4},
                                   now=101.0)
                packet(after, 101.01, 20)
                after.process_pending()
                st.save(*after.collect_dirty())
                actual = sorted((p['path'], p['started'], p['sent']) for p in st.load())
                expected = sorted([(r'C:\Old\app.exe', 100.0, 10),
                                   (r'C:\Other\app.exe', 100.4, 20)])
                self.assertEqual(actual, expected)
            finally:
                st.close()

    def test_v14_3_snapshot_with_traffic_does_not_absorb_next_start(self):
        m = fresh()
        m.sync_processes({100: ('old.exe', 1)}, now=100.0)
        m.fill_details(m.by_pid[100].uid,
                       {'path': r'C:\old.exe', 'started': 99.6}, now=100.0)
        packet(m, 100.0, 10)
        m.on_process_start(100, 100.02, r'C:\new.exe')
        packet(m, 100.03, 9)
        m.on_process_stop(100, 100.01)
        m.process_pending()
        self.assertEqual({p.path: p.sent for p in m.procs.values()},
                         {r'C:\old.exe': 10, r'C:\new.exe': 9})
        self.assertEqual(m.by_pid[100].path, r'C:\new.exe')
        self.assertIsNone(m.by_pid[100].ended)

    def test_v14_4_late_start_belongs_before_known_stop(self):
        m = fresh()
        packet(m, 1.0, 10)
        m.on_process_stop(100, 3.0)
        packet(m, 4.0, 9)
        m.on_process_start(100, 0.9, r'C:\old.exe')
        m.process_pending()
        old, new = sorted(m.procs.values(), key=lambda p: p.uid)
        self.assertEqual((old.path, old.started, old.ended, old.sent),
                         (r'C:\old.exe', 0.9, 3.0, 10))
        self.assertEqual((new.path, new.started, new.ended, new.sent),
                         ('', None, None, 9))
        self.assertIs(m.by_pid[100], new)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]], verbosity=2)

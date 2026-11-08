#!/usr/bin/env python3
"""Targeted Netmon 1.3 regression checks.

Prepared by static review and executed after environment recovery on 2026-11-08.
On original 1.3: 3 methods pass; 2 fail (3 failures including subtests).
Assertions describe correct behavior; V13-1 remains unfixed in the archive.
No Windows APIs, firewall changes or network requests are used.

Usage:
    python NETMON_REVIEW_V1_3_CHECKS.py path/to/extracted/netmon
"""
import argparse
from pathlib import Path
import sys
import unittest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path, help="Directory containing model.py")
args = parser.parse_args()
source = args.source.resolve()
if not (source / "model.py").is_file():
    parser.error("source must contain model.py")
sys.path.insert(0, str(source))

from model import Model


def fresh():
    model = Model()
    model.resolve_names = False
    return model


def traffic(model, timestamp, size):
    model.on_traffic(
        100, "TCP", True, size, "1.1.1.1", 443, "10.0.0.1", 50000, timestamp
    )


class Netmon13Review(unittest.TestCase):
    def test_v12_1_same_basename_different_full_path(self):
        for started in (100.4, None):
            with self.subTest(started=started):
                model = fresh()
                model.on_process_start(100, 100.0, r"C:\Old\app.exe")
                traffic(model, 100.1, 10)
                model.process_pending()
                old = model.by_pid[100]
                model.fill_details(
                    old.uid,
                    {"path": r"C:\Other\app.exe", "started": started},
                    now=100.5,
                )
                new = model.by_pid[100]
                self.assertIsNot(old, new)
                self.assertEqual(
                    (old.path, old.started, old.sent),
                    (r"C:\Old\app.exe", 100.0, 10),
                )
                self.assertEqual(
                    (new.path, new.started, new.sent),
                    (r"C:\Other\app.exe", started, 0),
                )

    def test_v12_1_same_full_path_new_instance(self):
        for gap in (0.1, 0.4):
            with self.subTest(gap=gap):
                model = fresh()
                model.on_process_start(100, 100.0, r"C:\App\app.exe")
                traffic(model, 100.05, 10)
                model.process_pending()
                old = model.by_pid[100]
                model.fill_details(
                    old.uid,
                    {"path": r"C:\App\app.exe", "started": 100.0 + gap},
                    now=100.0 + gap + 0.1,
                )
                new = model.by_pid[100]
                self.assertIsNot(old, new)
                self.assertEqual((old.started, old.sent), (100.0, 10))
                self.assertEqual((new.started, new.sent), (100.0 + gap, 0))

    def test_v12_2_delayed_traffic_with_close_lifetimes(self):
        for packets in ([(2.9, 7), (3.2, 9)], [(3.2, 9), (2.9, 7)]):
            with self.subTest(packets=packets):
                model = fresh()
                model.on_process_start(100, 1.0, r"C:\old.exe")
                model.on_process_stop(100, 3.0)
                model.on_process_start(100, 3.1, r"C:\new.exe")
                for timestamp, size in packets:
                    traffic(model, timestamp, size)
                model.process_pending()
                self.assertEqual(
                    {proc.name: proc.sent for proc in model.procs.values()},
                    {"old.exe": 7, "new.exe": 9},
                )

    def test_v13_1_start_after_explicit_stop_creates_new_instance(self):
        for new_path in (r"C:\new.exe", r"C:\old.exe"):
            with self.subTest(new_path=new_path):
                model = fresh()
                model.on_process_start(100, 100.0, r"C:\old.exe")
                model.on_process_stop(100, 100.01)
                model.on_process_start(100, 100.02, new_path)
                model.process_pending()
                self.assertEqual(len(model.procs), 2)
                old, new = sorted(model.procs.values(), key=lambda proc: proc.uid)
                self.assertEqual((old.started, old.ended), (100.0, 100.01))
                self.assertIs(model.by_pid[100], new)
                self.assertEqual((new.path, new.started, new.ended),
                                 (new_path, 100.02, None))

    def test_v13_1_new_traffic_is_not_charged_to_stopped_process(self):
        model = fresh()
        model.on_process_start(100, 100.0, r"C:\old.exe")
        traffic(model, 100.005, 10)
        model.on_process_stop(100, 100.01)
        model.on_process_start(100, 100.02, r"C:\new.exe")
        traffic(model, 100.03, 9)
        model.process_pending()
        self.assertEqual(
            {proc.name: proc.sent for proc in model.procs.values()},
            {"old.exe": 10, "new.exe": 9},
        )
        self.assertIsNone(model.by_pid[100].ended)


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]], verbosity=2)

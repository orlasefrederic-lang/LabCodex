from pathlib import Path
import json
import re

root = Path(__file__).resolve().parent
rows = []
for path in sorted((root / 'logs').glob('*Test*.log')):
    data = path.read_text()
    ok = re.search(r'OK \((\d+) tests?\)', data)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', data)
    if ok:
        result = dict(tests=int(ok[1]), failures=0, result='pass')
    elif failed:
        result = dict(tests=int(failed[1]), failures=int(failed[2]), result='fail')
        if int(failed[1]) == 0:
            result['result'] = 'setup-failed'
            result['interpretation'] = 'Class setup failed before any payload test method executed.'
    else:
        result = dict(result='incomplete-or-runner-error')
    if path.name.startswith('HostHistoryReviewTest'):
        result['interpretation'] = 'PASS means the historical-count defect was reproduced with a synthetic journal row.'
    if path.name.startswith('LockdownColdStartReviewTest'):
        result['interpretation'] = 'PASS means cold-start wrong proxy exclusion and failed HTTP were reproduced; existing recheck restored exact TCP/UDP and both app UIDs capture.'
    if path.name == 'HostRulesReviewTest.log':
        result['interpretation'] = 'Initial review-fixture bug: dotted HTTP path marker was rejected with404. Original log retained; corrected-fixture run separate.'
    rows.append(dict(log=path.name, **result))
(root / 'logs' / 'android-test-summary.json').write_text(json.dumps(rows, indent=2) + '\n')
print(json.dumps(rows, indent=2))

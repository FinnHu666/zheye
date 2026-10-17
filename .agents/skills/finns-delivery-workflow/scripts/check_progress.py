"""Read-only evidence gate check. Standard library only; no network or writes."""
import argparse
import hashlib
import json
from pathlib import Path


def inspect(root, state):
    root = Path(root).resolve()
    if state.get('schema_version') != 1 or not state.get('tasks'):
        raise ValueError('schema_version must be 1 and tasks must be nonempty')
    reports, ids = [], set()
    for task in state['tasks']:
        if not task.get('id') or task['id'] in ids or not task.get('version') or not task.get('gates'):
            raise ValueError('Each task needs unique id, version and gates')
        ids.add(task['id'])
        if state.get('required_gates') and [g['id'] for g in task['gates']] != state['required_gates']:
            raise ValueError('Missing or reordered required gates: ' + task['id'])
        first, seen = None, set()
        for gate in task['gates']:
            gid = gate['id']
            if gid in seen or gate.get('status') not in ('pending', 'pass', 'na'):
                raise ValueError('Duplicate gate or invalid status: ' + gid)
            seen.add(gid)
            if not gate.get('next_action') or not gate.get('user_action'):
                raise ValueError('Gate needs next_action and user_action: ' + gid)
            issues = []
            if gate['status'] == 'pending':
                issues.append(gate.get('reason') or '尚未完成')
            else:
                if gate['status'] == 'na' and not gate.get('reason'):
                    issues.append('不适用缺少理由')
                if not gate.get('evidence'):
                    issues.append('缺少证据')
                for evidence in gate.get('evidence', []):
                    path = (root / evidence['path']).resolve()
                    if not path.is_relative_to(root):
                        raise ValueError('Evidence outside project root')
                    if not path.is_file():
                        issues.append('证据不存在: ' + evidence['path'])
                    elif hashlib.sha256(path.read_bytes()).hexdigest() != evidence.get('sha256'):
                        issues.append('证据变化，需复核: ' + evidence['path'])
            if issues and first is None:
                first = {'stage': gid, 'reasons': issues, 'ai_next': gate['next_action'],
                         'user_next': gate['user_action']}
        reports.append({'task': task['id'], 'version': task['version'],
                        'next': first, 'registered_gates_satisfied': first is None})
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--state', required=True)
    args = parser.parse_args()
    try:
        path = (Path(args.root) / args.state).resolve()
        reports = inspect(args.root, json.loads(path.read_text(encoding='utf-8-sig')))
        print(json.dumps(reports, ensure_ascii=False, indent=2))
        return 2 if any(r['next'] for r in reports) else 0
    except (ValueError, KeyError, TypeError, OSError) as exc:
        print(json.dumps({'configuration_error': str(exc)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

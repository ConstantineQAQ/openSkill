#!/usr/bin/env python3
"""Launch the packaged Java server; exercise HTTP, DB outbox, scheduling, and exported Java CLI."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--static-dir', help='Existing frontend dist directory; never modified')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
jar = root / 'target/openskill-0.1.0-SNAPSHOT.jar'
cli = root / 'target/openskill-0.1.0-SNAPSHOT-skill-runtime.jar'
with tempfile.TemporaryDirectory(prefix='skill-java-smoke-') as temp:
    tmp = Path(temp)
    command = [java, '-jar', str(jar), '--spring.profiles.active=local', f'--server.port={port}',
               '--spring.datasource.url=jdbc:h2:mem:smoke;MODE=MySQL;DB_CLOSE_DELAY=-1',
               f'--skill.export.runtime-jar={cli}']
    if args.static_dir:
        command.append('--spring.web.resources.static-locations=' + Path(args.static_dir).resolve().as_uri() + '/')
    with (tmp / 'server.log').open('w') as log:
        proc = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        try:
            def request(path, body=None, method=None, raw=False):
                req = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(),
                                             {'Content-Type': 'application/json', 'Origin': base}, method=method)
                with urllib.request.urlopen(req, timeout=20) as response:
                    value = response.read()
                    return value if raw else json.loads(value)
            for _ in range(150):
                if proc.poll() is not None:
                    raise RuntimeError((tmp / 'server.log').read_text())
                try:
                    boot = request('/api/bootstrap')
                    break
                except (OSError, ValueError):
                    time.sleep(.2)
            else:
                raise TimeoutError('Server did not become ready')
            assert boot['runtime']['name'] == 'openSkill Java Runtime', boot['runtime']
            assert boot['runtime']['mode'] == 'database', boot['runtime']
            instruction = next(a for a in request('/api/adapters')['items'] if a['operation'] == 'instructions')
            assert not instruction['available'], instruction
            example = json.loads((root / 'examples/greeting-skill.json').read_text())
            greeting = request('/api/skills', example)
            greeting_run = request('/api/node-run', {'skillId': greeting['id'], 'version': 1,
                                                   'input': {'name': 'World'}})
            assert greeting_run['output']['text'] == 'Hello, World!', greeting_run
            workflow = boot['workflows'][0]
            result = request('/api/run', {'workflow': workflow, 'input': workflow['sampleInput']})
            assert result['status'] == 'succeeded', result
            assert len(result['steps'][2]['input']['alarms']) == 2
            published = request('/api/publish', {'workflow': workflow})
            run = request('/api/runs', {'target': {'type': 'skill', 'skillId': published['id'], 'version': 1},
                                        'input': workflow['sampleInput']})
            def terminal(run_id):
                for _ in range(100):
                    state = request('/api/runs/' + run_id)
                    if state['status'] not in ('queued', 'running', 'cancelling'):
                        assert state['status'] == 'succeeded', state
                        return state
                    time.sleep(.1)
                raise TimeoutError('Run did not finish')
            terminal(run['id'])
            at = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=2)).isoformat()
            schedule = request('/api/schedules', {'name': 'Smoke once', 'skillId': published['id'], 'version': 1,
                        'frequency': 'once', 'at': at, 'timezone': 'Asia/Shanghai', 'input': workflow['sampleInput']})
            for _ in range(100):
                task = request('/api/schedules/' + schedule['id'])
                if 'lastRunId' in task:
                    terminal(task['lastRunId'])
                    assert not task['enabled']
                    break
                time.sleep(.1)
            else:
                raise TimeoutError('Scheduler did not dispatch')
            bundle = request(f"/api/skills/{published['id']}/1/export", raw=True)
            (tmp / 'skill.zip').write_bytes(bundle)
            with zipfile.ZipFile(tmp / 'skill.zip') as archive:
                archive.extractall(tmp / 'bundle')
            exported = subprocess.run([java, '-jar', 'skill-runtime.jar', 'definition.json', 'input.json', '--demo'],
                                      cwd=tmp / 'bundle', capture_output=True, text=True, check=True)
            assert json.loads(exported.stdout) == result['output']
            if args.static_dir:
                assert request('/', raw=True) == (Path(args.static_dir) / 'index.html').read_bytes()
            print(json.dumps({'result': 'PASS', 'checks': ['openSkill branding + opt-in agent defaults + README greeting example', 'HTTP sync + canonical mappings', 'DB outbox asynchronous execution',
                'real once scheduler dispatch', 'ZIP export + standalone Java CLI output parity',
                'unchanged static frontend served' if args.static_dir else 'static frontend not requested'],
                'middleware': 'Local H2/database worker and standalone Java CLI'}, ensure_ascii=False, indent=2))
        except Exception:
            print((tmp / 'server.log').read_text()[-12000:])
            raise
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

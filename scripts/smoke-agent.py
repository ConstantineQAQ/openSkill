#!/usr/bin/env python3
"""Isolated HTTP regression for imported skills; uses a fake CLI, never contacts a model."""
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
with tempfile.TemporaryDirectory(prefix='skill-agent-http-') as directory:
    temp = Path(directory)
    command = temp / 'fake-codex'
    command.write_text('''#!/bin/sh
set -eu
while [ "$#" -gt 0 ]; do
  case "$1" in
    --cd) workspace="$2"; shift;;
    --output-last-message) output="$2"; shift;;
  esac
  shift
done
cat >/dev/null
[ -f "$workspace/input.json" ]
if [ -f "$workspace/skill/sample/SKILL.md" ]; then
  [ "$(cat "$workspace/skill/sample/references/value.txt")" = 'packaged-resource' ]
  printf '%s' '{"success":true,"text":"zip fixture result"}' > "$output"
else
  [ -f "$workspace/skill/SKILL.md" ]
  printf '%s' '{"success":true,"text":"markdown fixture result"}' > "$output"
fi
''')
    command.chmod(0o700)
    args = [java, '-jar', str(root / 'target/openskill-0.1.0-SNAPSHOT.jar'),
            '--spring.profiles.active=local', f'--server.port={port}',
            '--spring.datasource.url=jdbc:h2:mem:agent-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1',
            '--skill.agent.mode=codex', f'--skill.agent.command={command}',
            '--skill.agent.network-enabled=false', '--skill.agent.provider.source=server',
            '--skill.agent.provider.base-url=https://fixture.invalid/v1',
            '--skill.agent.provider.model=fixture-model',
            '--skill.agent.provider.api-key=fixture-not-a-real-key']
    with (temp / 'server.log').open('w') as log:
        proc = subprocess.Popen(args, cwd=temp, stdout=log, stderr=subprocess.STDOUT)
        try:
            def request(path, body=None, method=None, raw=False, content_type='application/json'):
                data = None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode()
                req = urllib.request.Request(base + path, data, {'Content-Type': content_type, 'Origin': base}, method=method)
                with urllib.request.urlopen(req, timeout=30) as response:
                    value = response.read()
                    return value if raw else json.loads(value)

            for _ in range(150):
                if proc.poll() is not None:
                    raise RuntimeError((temp / 'server.log').read_text())
                try:
                    request('/api/bootstrap')
                    break
                except OSError:
                    time.sleep(.2)
            else:
                raise TimeoutError('Server did not start')

            md = '---\nname: http-fixture\ndescription: test only\n---\n# Test fixture\n'
            one = request('/api/skills/import', {'markdown': md})
            assert one['inputs'][0]['key'] == 'prompt'
            run = request('/api/node-run', {'skillId': one['id'], 'version': 1, 'input': {'prompt': 'test'}})
            assert run['output']['text'] == 'markdown fixture result', run

            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w') as bundle:
                bundle.writestr('sample/SKILL.md', md)
                bundle.writestr('sample/references/value.txt', 'packaged-resource')
                bundle.writestr('sample/scripts/task.sh', 'exit 99')
            data = archive.getvalue()
            boundary = 'skill-agent-fixture-boundary'
            payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.zip"\r\n'
                       'Content-Type: application/zip\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
            two = request('/api/skills/import', payload, content_type=f'multipart/form-data; boundary={boundary}')
            assert two['id'] == one['id'] and two['version'] == 2
            run = request('/api/node-run', {'skillId': two['id'], 'version': 2, 'input': {}})
            assert run['output']['text'] == 'zip fixture result', run

            template = request('/api/skills', {'name': 'http-template', 'title': 'HTTP template',
                'operation': 'template', 'config': {'template': 'formatted {{value}}'},
                'inputs': [{'key': 'value', 'type': 'string', 'required': True}],
                'outputs': [{'key': 'text', 'type': 'string', 'required': True}]})
            workflow = request('/api/workflows', {'name': 'http-flow', 'title': 'HTTP flow', 'inputs': [],
                'sampleInput': {}, 'steps': [
                    {'id': 'agent', 'skill': two, 'bindings': {}, 'onEmpty': 'stop'},
                    {'id': 'format', 'skill': template, 'onEmpty': 'stop', 'bindings': {
                        'value': {'mode': 'path', 'path': 'steps.agent.text', 'transform': 'identity', 'confirmed': True}}}]})
            run = request('/api/run', {'workflow': workflow, 'input': {}})
            assert run['output']['text'] == 'formatted zip fixture result', run
            deleted = request('/api/skills/' + two['id'] + '/delete', {}, method='POST')
            assert deleted == {'id': two['id'], 'deleted': True}, deleted
            assert not any(s['id'] == two['id'] for s in request('/api/bootstrap')['skills'])
            run = request('/api/run', {'workflow': workflow, 'input': {}})
            assert run['status'] == 'succeeded', run
            assert request('/api/skills/' + two['id'] + '/2/export', raw=True) == data
            three = request('/api/skills/import', {'markdown': md})
            assert three['id'] == two['id'] and three['version'] == 3
            print('PASS: MD/ZIP HTTP trials, packaged resources, downstream mapping, POST deletion, historical references, exact ZIP export and reimport; fake CLI only, no model calls.')
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

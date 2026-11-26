#!/usr/bin/env python3
"""Verify real multipart upload, immutable ZIP export, restart persistence and Chinese logs."""
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
jar = root / 'target/openskill-0.1.0-SNAPSHOT.jar'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'

with tempfile.TemporaryDirectory(prefix='skill-package-smoke-') as temp:
    tmp = Path(temp)
    log_path = tmp / 'server.log'
    command = [java, '-jar', str(jar), '--spring.profiles.active=local', f'--server.port={port}',
               f'--spring.datasource.url=jdbc:h2:file:{tmp}/database;MODE=MySQL;DATABASE_TO_LOWER=TRUE',
               '--skill.worker.enabled=false']
    env = {**os.environ, 'SKILL_LOG_DIR': str(tmp / 'logs')}

    def request(path, body=None, content_type=None):
        headers = {'Origin': base, 'Sec-Fetch-Site': 'same-origin'}
        if content_type:
            headers['Content-Type'] = content_type
        with urllib.request.urlopen(urllib.request.Request(base + path, body, headers), timeout=30) as response:
            return response.read(), response.headers

    def start(log):
        process = subprocess.Popen(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(150):
            if process.poll() is not None:
                raise RuntimeError(log_path.read_text())
            try:
                request('/api/bootstrap')
                return process
            except (OSError, ValueError):
                time.sleep(.2)
        stop(process)
        raise TimeoutError('Server did not become ready')

    def stop(process):
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    name = 'package-smoke-' + uuid.uuid4().hex
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as package:
        package.comment = '完整包注释'.encode()
        package.writestr('my-skill/SKILL.md', f'---\nname: {name}\ndescription: 导入导出冒烟\n---\n资源完整保留\n')
        entry = zipfile.ZipInfo('my-skill/scripts/run.sh')
        entry.create_system = 3
        entry.external_attr = (0o100755 << 16)
        package.writestr(entry, '#!/bin/sh\necho must-not-run\n')
        package.writestr('my-skill/references/参考.md', 'package-secret-do-not-log')
        package.writestr('my-skill/assets/binary.dat', os.urandom(2 * 1024 * 1024))
        package.writestr('my-skill/empty/', '')
        package.writestr('my-skill/.hidden', 'hidden')
    archive = stream.getvalue()
    boundary = 'skill-boundary-' + uuid.uuid4().hex
    multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="skill.zip"\r\n'
                 'Content-Type: application/zip\r\n\r\n').encode() + archive + f'\r\n--{boundary}--\r\n'.encode()
    content_type = f'multipart/form-data; boundary={boundary}'
    with log_path.open('w') as log:
        process = start(log)
        try:
            uploaded, headers = request('/api/skills/import', multipart, content_type)
            skill = json.loads(uploaded)
            trace = headers['X-Trace-Id']
            export_path = f"/api/skills/{skill['id']}/1/export"
            downloaded, headers = request(export_path)
            assert downloaded == archive
            assert headers['Content-Type'] == 'application/zip'
            assert 'attachment' in headers['Content-Disposition']
            stop(process)
            process = start(log)
            assert request(export_path)[0] == archive, 'Package did not survive restart'
            second = json.loads(request('/api/skills/import', multipart, content_type)[0])
            assert second['id'] == skill['id'] and second['version'] == 2
            assert request(export_path)[0] == archive
            assert request(f"/api/skills/{skill['id']}/2/export")[0] == archive
        finally:
            stop(process)
    logs = (tmp / 'logs/openskill.log').read_text()
    assert 'Skill 完整包导入成功' in logs and 'Skill 导出成功' in logs
    assert '接口请求结束' in logs and f'traceId={trace}' in logs
    assert 'package-secret-do-not-log' not in logs and 'must-not-run' not in logs
    assert 'multiple SLF4J providers' not in log_path.read_text()
    print(json.dumps({'result': 'PASS', 'checks': [
        'real HTTP multipart upload (>2MB)', 'byte-identical ZIP export including binary/modes/comments',
        'file database persistence across restart', 'immutable reimport versions',
        'Log4j2 Chinese console/file logs with traceId and no package body']}, ensure_ascii=False))

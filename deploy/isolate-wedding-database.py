#!/usr/bin/env python3
"""Restrict Wedding DB connections without changing lunch source, data or service.

Run with sudo on the shared PostgreSQL host. PostgreSQL remains running; only
authentication rules are reloaded. postgres peer administration is preserved.
"""
import datetime
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

START = '# BEGIN WEDDING DATABASE ISOLATION'
END = '# END WEDDING DATABASE ISOLATION'
RULES = '''# BEGIN WEDDING DATABASE ISOLATION
local wedding wedding_app,wedding_migrator scram-sha-256
local all wedding_app,wedding_migrator reject
host wedding wedding_app,wedding_migrator 127.0.0.1/32 scram-sha-256
host wedding wedding_app,wedding_migrator ::1/128 scram-sha-256
host all wedding_app,wedding_migrator 0.0.0.0/0 reject
host all wedding_app,wedding_migrator ::/0 reject
local wedding postgres peer
local wedding all reject
host wedding all 0.0.0.0/0 reject
host wedding all ::/0 reject
# END WEDDING DATABASE ISOLATION
'''

def sql(query):
    return subprocess.check_output(['sudo','-u','postgres','psql','-X','-v','ON_ERROR_STOP=1','-d','postgres','-Atc',query],text=True).strip()

def main():
    if os.geteuid() != 0:
        raise SystemExit('Run with sudo.')
    if sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('wedding_app','wedding_migrator') AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb") != '2':
        raise SystemExit('Expected restricted Wedding roles were not found.')
    path=Path(sql('SHOW hba_file')).resolve(strict=True)
    if path.name != 'pg_hba.conf':
        raise SystemExit('Unexpected HBA file path.')
    original=path.read_text()
    remaining=original
    if START in remaining:
        if remaining.count(START)!=1 or remaining.count(END)!=1:
            raise SystemExit('Ambiguous existing isolation block.')
        before,rest=remaining.split(START,1)
        _,after=rest.split(END,1)
        remaining=before+after.lstrip('\n')
    result=RULES+remaining
    if result==original:
        print('Wedding authentication isolation already configured.')
        return
    stat=path.stat()
    backup=path.with_name(path.name+'.before-wedding-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    shutil.copy2(path,backup)
    os.chown(backup,stat.st_uid,stat.st_gid)
    temporary=None
    try:
        with tempfile.NamedTemporaryFile(mode='w',dir=path.parent,delete=False) as stream:
            temporary=Path(stream.name)
            stream.write(result)
        os.chmod(temporary,stat.st_mode & 0o777)
        os.chown(temporary,stat.st_uid,stat.st_gid)
        os.replace(temporary,path)
        if sql('SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL')!='0':
            raise RuntimeError('Authentication configuration validation failed.')
        if sql('SELECT pg_reload_conf()')!='t':
            raise RuntimeError('Configuration reload failed.')
    except Exception:
        shutil.copy2(backup,path)
        os.chown(path,stat.st_uid,stat.st_gid)
        sql('SELECT pg_reload_conf()')
        raise
    finally:
        if temporary and temporary.exists(): temporary.unlink()
    print('Wedding DB and roles restricted to their own loopback connections; peer administration preserved.')
    print('Rollback copy: '+str(backup))

if __name__=='__main__': main()

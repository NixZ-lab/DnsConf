"""Publish counts/status only. Never expose profile IDs, domains, IPs or errors."""
import argparse
import json
import os
from pathlib import Path

ENVIRONMENTS=('DNS','DNS_WIFE','DNS_DIRECTOR','DNS_PV')

def profile_row(environment,mode,job_status,skipped,directory):
    row=dict(environment=environment,mode=mode,added=None,updated=None,deleted=None,conflicts=None,status='Failed')
    if skipped:
        row['status']='Not onboarded';return row
    reports=list(Path(directory).glob('*/result.json'))
    plans=list(Path(directory).glob('*/plan.json'))
    if reports:
        if len(reports)!=1:raise ValueError('Ambiguous profile report')
        r=json.loads(reports[0].read_text())
        row.update({k:r[k] for k in ('added','updated','deleted','conflicts','status')})
    elif plans and mode in {'preview','dry-run'}:
        if len(plans)!=1:raise ValueError('Ambiguous profile plan')
        p=json.loads(plans[0].read_text());create={r['name'] for r in p['create']};delete={r['name'] for r in p['delete']};replacements=create&delete
        row.update(added=len(p['create'])-len(replacements),updated=len(replacements),deleted=sum(r['name'] not in replacements for r in p['delete']),conflicts=len(p['conflicts']),status='Preview')
    elif mode=='adopt' and job_status=='success':
        row.update(added=0,updated=0,deleted=0,status='Adopted')
    if job_status!='success':row['status']='Failed'
    return row

def markdown(rows):
    return '\n'.join(['| Profile | Added | Updated | Deleted | Conflicts | Result |','|---|---:|---:|---:|---:|---|']+[
        '| '+' | '.join(str(r[k]) if r[k] is not None else '—' for k in ('environment','added','updated','deleted','conflicts','status'))+' |' for r in rows])+'\n'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--aggregate',action='store_true');a=parser.parse_args()
    if a.aggregate:
        rows=[]
        for path in sorted(Path('results').glob('profile-result-*.json')):
            r=json.loads(path.read_text())
            if set(r)!={'environment','mode','added','updated','deleted','conflicts','status'} or r['environment'] not in ENVIRONMENTS:
                raise ValueError('Unexpected public report fields')
            if any(r[k] is not None and (type(r[k]) is not int or r[k]<0) for k in ('added','updated','deleted','conflicts')):
                raise ValueError('Invalid count')
            if r['status'] not in {'Success','Failed','Preview','Adopted','Not onboarded'}:raise ValueError('Invalid status')
            rows.append(r)
        text=markdown(rows)
    else:
        env=os.environ['PROFILE_ENVIRONMENT'];assert env in ENVIRONMENTS
        row=profile_row(env,os.environ.get('SYNC_MODE','preview'),os.environ['JOB_STATUS'],os.environ.get('PROFILE_SKIPPED')=='true',os.environ.get('SYNC_DIR','nextdns-sync'))
        Path(f'profile-result-{env}.json').write_text(json.dumps(row,indent=2)+'\n')
        text=markdown([row])
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as file:file.write(text)
    print(text)

if __name__=='__main__':main()

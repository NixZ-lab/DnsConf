"""Fail closed before accessing a profile; disabled Sync needs no credentials."""
import os


def validate(env):
    def require(condition, message):
        if not condition:
            raise ValueError(message)

    mode = env['SYNC_MODE']
    require(mode in {'preview', 'dry-run', 'sync', 'adopt', 'apply'}, 'Unknown mode')
    require(env['SYNC_ENABLED'] in {'true', 'false'}, 'SYNC_ENABLED must be true or false')
    if mode == 'sync':
        require(env['GITHUB_REF'] == 'refs/heads/main', 'Sync is restricted to main')
        require(env['INITIALIZE'] == 'false', 'Sync cannot initialize or adopt')
        if env['SYNC_ENABLED'] != 'true':
            return True
    require(env['DNS'].strip().upper() == 'NEXTDNS', 'This workflow only supports NextDNS')
    profile = env['CLIENT_ID']
    require(len(profile) == 6 and all(c in '0123456789abcdef' for c in profile), 'One NextDNS profile per environment required')
    require(env['MAX_DELETE'].isdigit(), 'max_delete must be a non-negative integer')
    if env['DRY_RUN'] == 'false':
        require(env['GITHUB_REF'] == 'refs/heads/main', 'Apply is restricted to main')
        require(env['SELECTED_ENVIRONMENT'] != 'ALL', 'Review and apply one environment at a time')
        require(env['REVIEW_RUN_ID'].isdigit(), 'A reviewed preview run ID is required')
    if mode == 'adopt':
        require(env['ADOPT_IDS'].strip(), 'Explicitly reviewed IDs are required for adoption')
    return False


if __name__ == '__main__':
    skip = validate(os.environ)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'skip={str(skip).lower()}\n')

"""Verify typed host/WASM trace boundaries from run_customer360.sh (no node required)."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

cli=argparse.ArgumentParser(description=__doc__)
cli.add_argument('log',type=Path)
cli.add_argument('--disabled',action='store_true')
args=cli.parse_args()
log=args.log.read_text()
assert 'CUSTOMER 360 CHECKS PASSED' in log and 'ALL COMPUTE CHECKS PASSED' in log
assert '[SYBASE-TRACE-ERROR]' not in log
prefix='[SYBASE-TRACE] '
events=[json.loads(line[len(prefix):]) for line in log.splitlines() if line.startswith(prefix)]
if args.disabled:
    assert not events
    print('Customer 360 trace-disabled checks passed, including persistent data after restart.')
    raise SystemExit()
calls=defaultdict(list)
for event in events:
    if event['procedure'].startswith('sp_') or event['procedure']=='typed_roundtrip':
        calls[event['call_id']].append(event)
assert len(calls)==30, f'Expected 23 responses + 6 failures + one nested call, got {len(calls)}'
root=Path(__file__).resolve().parent
failures=0
fetches=[]
for call_id,trace in calls.items():
    assert [e['sequence'] for e in trace]==list(range(1,len(trace)+1))
    select=lambda kind:[e for e in trace if e['event']==kind]
    procedure=trace[0]['procedure']
    abi=json.loads((root/'generated'/f'{procedure}.abi.json').read_text())
    for start in select('SQL_START'):
        assert start['sql'].count('?')==len(start['parameters'])
        ends=[e for e in trace if e['event'] in ('SQL_END','SQL_ERROR') and e['sql_call']==start['sql_call']]
        assert len(ends)==1 and ends[0]['sequence']>start['sequence']
    for returned in select('HOST_RETURN'):
        op=abi['operations'][returned['operation']]
        raw=returned['wasm_values']
        assert len(raw)==op['results']
        if op['kind'] in ('assign','dml','insert','result_set','open_cursor','fetch_cursor'):
            count=raw[0] if op['kind']=='insert' else raw[-1]
            assigned=trace[returned['sequence']]
            assert assigned['event']=='ROWCOUNT_SET' and assigned['value']==count, (procedure,op,assigned)
    for nested in select('EXECUTE_PROCEDURE'):
        assert calls[nested['child_call_id']][0]['procedure']==nested['callee']
    fetches+=select('CURSOR_FETCH')
    if trace[-1]['event']=='CALL_ERROR':
        failures+=1
        if procedure=='sp_add_order_item':
            assert any(e['action']=='rollback_on_exit' for e in select('TRANSACTION'))
        continue
    assert trace[0]['event']=='CALL_START' and trace[-1]['event']=='CALL_END'
    returned,=select('WASM_RETURN')
    assert returned['values'][0]==trace[-1]['status']
    output=select('OUTPUT')
    assert [e['handle'] for e in output]==returned['values'][1:]
    assert {e['parameter'][1:]:e['value'] for e in output}==trace[-1]['outputs']
    sets=select('RESULT_SET')
    assert [e['index'] for e in sets]==list(range(1,len(sets)+1))
    if procedure=='sp_customer_360' and trace[-1]['status']==0:
        assert len(sets)==6
assert failures==6
assert any(e['sqlstatus']==0 and e['rowcount']==1 for e in fetches)
assert any(e['sqlstatus']==2 and e['rowcount']==0 for e in fetches)
print(f'Typed trace passed: {len(calls)} WASM calls, {failures} expected failures, SQL/count/output/cursor/transaction boundaries verified.')

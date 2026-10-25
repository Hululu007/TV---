const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/assets/js/lib/ui.js'), 'utf8');
function context() {
    const calls = [];
    const scope = vm.createContext({__SPIDER_DIALOG_NATIVE__: {call(method, json) { calls.push({method, ...JSON.parse(json)}); return '{}'; }}});
    vm.runInContext(source, scope);
    return {scope, calls, dialog: scope.dialog, deliver: scope.__SPIDER_DIALOG_DELIVER__};
}
const tick = () => new Promise(resolve => setImmediate(resolve));
(async () => {
    const one = context(), two = context();
    const first = one.dialog({title: 'First'}), independent = two.dialog({title: 'Other'});
    assert.equal(one.calls[0].options.title, 'First');
    one.deliver('result', 1, JSON.stringify({value: {button: 'ok', values: {code: '1234'}}}));
    assert.equal((await first).values.code, '1234');
    let unrelated = false; independent.then(() => unrelated = true);
    await tick(); assert.equal(unrelated, false);
    two.deliver('result', 1, '{"value":null}'); assert.equal(await independent, null);
    const sequence = one.dialog({title: 'Second', onAction: async event => ({message: event.values.code, close: false})});
    one.deliver('action', 4, JSON.stringify({request: 2, event: {button: 'verify', values: {code: '5678'}}}));
    await tick();
    assert.equal(one.calls.at(-1).method, 'actionResult');
    assert.equal(one.calls.at(-1).value.message, '5678');
    assert.equal(one.calls.at(-1).value.close, false);
    assert.equal(one.calls[1].options.onAction, undefined);
    one.deliver('result', 2, '{"value":{"button":"done","values":{}}}'); await sequence;
    await assert.rejects(one.dialog(null));
    await assert.rejects(one.dialog({onAction: 'bad'}));
    const invalid = one.dialog({onAction: () => []});
    one.deliver('action', 5, '{"request":3,"event":{"button":"ok","values":{}}}');
    await tick(); assert.equal(one.calls.at(-1).error, true);
    const rejected = assert.rejects(invalid, /closed/);
    one.deliver('close', 0, ''); await rejected;
    await assert.rejects(one.dialog({}), /closed/);
    console.log('Dialog facade: protocol, sequential requests, callbacks, isolation and cancellation passed');
})().catch(error => { console.error(error); process.exitCode = 1; });

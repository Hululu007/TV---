(() => {
    const native = globalThis.__SPIDER_DIALOG_NATIVE__;
    const requests = new Map();
    let nextId = 0;
    let closed = false;
    function invoke(method, params) {
        const result = JSON.parse(native.call(method, JSON.stringify(params)));
        if (result.error) throw new Error(result.error);
    }
    const sharedDialog = function(options) {
        if (!options || typeof options !== 'object' || Array.isArray(options)) return Promise.reject(new TypeError('Dialog options must be an object'));
        const {onAction, ...fields} = options;
        if (onAction != null && typeof onAction !== 'function') return Promise.reject(new TypeError('Dialog onAction must be a function'));
        if (closed) return Promise.reject(new Error('Dialog bridge closed'));
        const id = ++nextId;
        return new Promise((resolve, reject) => {
            requests.set(id, {resolve, reject, onAction});
            try { invoke('open', {id, options: fields, callback: onAction != null}); }
            catch (error) { requests.delete(id); reject(error); }
        });
    };
    // A legacy JAR may already provide its own dialog API. Preserve that contract.
    if (typeof globalThis.dialog === 'undefined') globalThis.dialog = sharedDialog;
    globalThis.__SPIDER_DIALOG_DELIVER__ = function(kind, id, payload) {
        if (kind === 'close') {
            closed = true;
            for (const item of requests.values()) item.reject(new Error('Dialog bridge closed'));
            requests.clear();
            return;
        }
        if (closed) return;
        const value = JSON.parse(payload);
        if (kind === 'action') {
            const item = requests.get(value.request);
            if (!item || !item.onAction) return;
            Promise.resolve().then(() => item.onAction(value.event)).then(update => {
                if (update != null && (typeof update !== 'object' || Array.isArray(update))) throw new TypeError('Dialog action result must be an object');
                if (!closed && requests.has(value.request)) invoke('actionResult', {id, value: update == null ? null : update});
            }).catch(() => {
                if (!closed && requests.has(value.request)) {
                    try { invoke('actionResult', {id, error: true}); } catch (_) {}
                }
            });
            return;
        }
        const item = requests.get(id);
        if (!item) return;
        requests.delete(id);
        if (value.error) item.reject(new Error(value.error));
        else item.resolve(value.value == null ? null : value.value);
    };
})();

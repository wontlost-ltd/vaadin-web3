const VERSION = '2.0.0';

export function installCallsMethods(prototype) {
  prototype.getCapabilities = function (chainIds) {
    return getCapabilities(this, chainIds).catch((error) => { this._error(error); throw error; });
  };
  prototype.sendCalls = function (request) {
    return sendCalls(this, request).catch((error) => { this._error(error); throw error; });
  };
  prototype.getCallsStatus = function (id) {
    return getCallsStatus(this, id).catch((error) => { this._error(error); throw error; });
  };
  prototype.showCallsStatus = function (id) {
    return showCallsStatus(this, id).catch((error) => { this._error(error); throw error; });
  };
}

export async function getCapabilities(component, chainIds) {
  const provider = requireProvider(component);
  if (!component.account) throw walletError(4100, 'No wallet connected');
  const chains = chainIds === undefined ? undefined : validateChainIds(chainIds);
  return provider.request({ method: 'wallet_getCapabilities',
    params: chains === undefined ? [component.account] : [component.account, chains] });
}

export async function sendCalls(component, request) {
  const provider = requireProvider(component);
  if (!request || typeof request !== 'object' || Array.isArray(request)) throw walletError(-32602, 'Calls request must be an object');
  if (!Array.isArray(request.calls) || request.calls.length === 0) throw walletError(-32602, 'Calls request must contain at least one call');
  if (typeof request.atomicRequired !== 'boolean') throw walletError(-32602, 'atomicRequired must be a boolean');
  const payload = { version: VERSION };
  for (const key of ['id', 'from']) if (request[key] !== undefined) payload[key] = request[key];
  payload.chainId = normalizeChainId(request.chainId);
  payload.atomicRequired = request.atomicRequired;
  payload.calls = request.calls.map(validateCall);
  if (request.capabilities !== undefined) payload.capabilities = request.capabilities;
  return provider.request({ method: 'wallet_sendCalls', params: [payload] });
}

export async function getCallsStatus(component, id) {
  requireId(id);
  return requireProvider(component).request({ method: 'wallet_getCallsStatus', params: [id] });
}

export async function showCallsStatus(component, id) {
  requireId(id);
  return requireProvider(component).request({ method: 'wallet_showCallsStatus', params: [id] });
}

function requireProvider(component) {
  // 用 4900（未连接）而非 -32601：-32601 表示"方法不支持"，会被误判为可回退
  if (!component.provider?.request) throw walletError(4900, 'No EIP-1193 provider found');
  return component.provider;
}

function normalizeChainId(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return `0x${value.toString(16)}`;
  if (typeof value === 'string' && /^(0x[\da-f]+|\d+)$/i.test(value)) return `0x${BigInt(value).toString(16)}`;
  throw walletError(-32602, 'chainId must be a non-negative integer or hex string');
}

function validateChainIds(values) {
  if (!Array.isArray(values)) throw walletError(-32602, 'chainIds must be an array');
  return values.map(normalizeChainId);
}

function validateCall(call) {
  if (!call || typeof call !== 'object' || Array.isArray(call)) throw walletError(-32602, 'Each call must be an object');
  const result = {};
  for (const key of ['to', 'data', 'value']) {
    if (call[key] !== undefined) {
      if (typeof call[key] !== 'string') throw walletError(-32602, `${key} must be a string`);
      result[key] = call[key];
    }
  }
  if (!result.to && !result.data) throw walletError(-32602, 'Each call must include to or data');
  if (call.capabilities !== undefined) result.capabilities = call.capabilities;
  return result;
}

function requireId(id) {
  if (typeof id !== 'string' || id.length === 0) throw walletError(-32602, 'id must be a non-empty string');
}

function walletError(code, message) { return Object.assign(new Error(message), { code }); }

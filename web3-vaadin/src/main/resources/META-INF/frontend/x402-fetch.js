export const X402_HEADERS = Object.freeze({
  required: 'PAYMENT-REQUIRED',
  signature: 'PAYMENT-SIGNATURE',
  response: 'PAYMENT-RESPONSE',
  siwx: 'SIGN-IN-WITH-X'
});

globalThis.x402Fetch = x402Fetch;

export class X402PaymentError extends Error {
  constructor(code, message = code, response = null) {
    super(message);
    this.name = 'X402PaymentError';
    this.code = code;
    if (response) {
      this.response = response;
    }
  }
}

export function encodeX402Header(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

export function decodeX402Header(value) {
  if (typeof value !== 'string' || !value || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new X402PaymentError('X402_INVALID_HEADER');
  }
  const body = value.replace(/=+$/, '');
  if (body.length % 4 === 1) {
    throw new X402PaymentError('X402_INVALID_HEADER');
  }
  try {
    const binary = atob(body);
    const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
    const result = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!result || typeof result !== 'object' || Array.isArray(result)) {
      throw new Error('not object');
    }
    if (result.x402Version !== undefined && result.x402Version !== 2) {
      throw new X402PaymentError('X402_UNSUPPORTED_VERSION');
    }
    return result;
  } catch (error) {
    if (error instanceof X402PaymentError) {
      throw error;
    }
    throw new X402PaymentError('X402_INVALID_HEADER');
  }
}

export function isReplayableRequest(input, init = {}) {
  const body = init.body;
  if (input instanceof Request && (input.bodyUsed || input.body?.locked)) {
    return false;
  }
  if (body && typeof body.getReader === 'function') {
    return false;
  }
  return true;
}

export function createEip3009Authorization(requirement, account, nowSeconds = Math.floor(Date.now() / 1000)) {
  const networkMatch = /^eip155:([1-9][0-9]*)$/.exec(requirement?.network || '');
  const timeout = Number(requirement?.maxTimeoutSeconds);
  const name = requirement?.extra?.name;
  const version = requirement?.extra?.version;
  if (requirement?.scheme !== 'exact' || requirement?.extra?.assetTransferMethod !== 'eip3009'
      || requirement?.extra?.paymentFlow !== 'authorization'
      || !networkMatch || !Number.isSafeInteger(timeout) || timeout < 1
      || typeof requirement.amount !== 'string' || !/^(0|[1-9][0-9]*)$/.test(requirement.amount)
      || BigInt(requirement.amount) < 1n
      || typeof requirement.asset !== 'string' || typeof requirement.payTo !== 'string'
      || typeof account !== 'string' || !account
      || typeof name !== 'string' || !name || typeof version !== 'string' || !version) {
    throw new X402PaymentError('X402_INVALID_REQUIREMENT');
  }
  if (!globalThis.crypto?.getRandomValues) {
    throw new X402PaymentError('X402_CRYPTO_UNAVAILABLE');
  }

  const now = BigInt(nowSeconds);
  const validAfter = now - 600n;
  if (validAfter < 0n) {
    throw new X402PaymentError('X402_INVALID_TIME');
  }
  const nonceBytes = globalThis.crypto.getRandomValues(new Uint8Array(32));
  const nonce = `0x${Array.from(nonceBytes, byte => byte.toString(16).padStart(2, '0')).join('')}`;
  const authorization = {
    from: account,
    to: requirement.payTo,
    value: requirement.amount,
    validAfter: validAfter.toString(),
    validBefore: (now + BigInt(timeout)).toString(),
    nonce
  };
  const typedData = {
    domain: {
      name,
      version,
      chainId: networkMatch[1],
      verifyingContract: requirement.asset
    },
    primaryType: 'TransferWithAuthorization',
    types: {
      EIP712Domain: [
        { name: 'name', type: 'string' },
        { name: 'version', type: 'string' },
        { name: 'chainId', type: 'uint256' },
        { name: 'verifyingContract', type: 'address' }
      ],
      TransferWithAuthorization: [
        { name: 'from', type: 'address' },
        { name: 'to', type: 'address' },
        { name: 'value', type: 'uint256' },
        { name: 'validAfter', type: 'uint256' },
        { name: 'validBefore', type: 'uint256' },
        { name: 'nonce', type: 'bytes32' }
      ]
    },
    message: authorization
  };
  return { authorization, typedDataJson: JSON.stringify(typedData) };
}

export async function x402Fetch(input, init = {}, options = {}) {
  const fetcher = options.fetch || globalThis.fetch;
  const notify = state => options.onState?.(state);
  const replayableInput = isReplayableRequest(input, init);
  let effectiveRequest;
  try {
    let requestInput = input;
    if (!(input instanceof Request)) {
      try {
        requestInput = new URL(String(input));
      } catch {
        const base = globalThis.location?.href || 'http://localhost/';
        requestInput = new URL(String(input), base);
      }
    }
    effectiveRequest = new Request(requestInput, init);
  } catch {
    throw new X402PaymentError('X402_REQUEST_INVALID');
  }
  const response = await fetcher(effectiveRequest.clone());
  if (response.status !== 402) {
    return response;
  }

  const requiredHeader = response.headers.get(X402_HEADERS.required);
  if (!requiredHeader) {
    throw new X402PaymentError('X402_CHALLENGE_MISSING');
  }
  const challenge = decodeX402Header(requiredHeader);
  if (challenge.x402Version !== 2 || !challenge.resource || !Array.isArray(challenge.accepts)
      || challenge.accepts.length === 0) {
    throw new X402PaymentError('X402_INVALID_CHALLENGE');
  }
  notify({ state: 'PAYMENT_REQUIRED', resource: challenge.resource, accepted: challenge.accepts });

  let siwxProof;
  const siwxChallenge = challenge.extensions?.['sign-in-with-x'];
  if (siwxChallenge) {
    if (!options.siwxProvider) {
      if (siwxChallenge.required) {
        throw new X402PaymentError('X402_SIWX_REQUIRED');
      }
    } else {
      const account = walletAccount(options.wallet);
      try {
        siwxProof = await options.siwxProvider({
          challenge: siwxChallenge,
          account,
          resource: challenge.resource,
          wallet: options.wallet
        });
      } catch {
        throw new X402PaymentError('X402_SIWX_REJECTED');
      }
      if (!siwxProof) {
        throw new X402PaymentError('X402_SIWX_REJECTED');
      }
    }
  }

  const requirement = await chooseRequirement(challenge.accepts, options);
  if (!requirement) {
    throw new X402PaymentError('X402_PAYMENT_CANCELLED');
  }
  const wallet = options.wallet;
  const account = walletAccount(wallet);
  if (!account) {
    throw new X402PaymentError('X402_WALLET_NOT_CONNECTED');
  }
  if (!chainMatches(wallet.chainId, requirement.network)) {
    throw new X402PaymentError('X402_WRONG_NETWORK');
  }
  if (effectiveRequest.signal?.aborted) {
    throw new X402PaymentError('X402_ABORTED');
  }

  const callerHeaders = new Headers(effectiveRequest.headers);
  if (callerHeaders.has(X402_HEADERS.signature) || callerHeaders.has(X402_HEADERS.siwx)) {
    throw new X402PaymentError('X402_PAYMENT_HEADER_CONFLICT');
  }
  if (!replayableInput && typeof options.replayRequest !== 'function') {
    throw new X402PaymentError('X402_UNREPLAYABLE_REQUEST');
  }
  const retryRequest = await createReplay(effectiveRequest, {}, options);
  if (!retryRequest) {
    throw new X402PaymentError('X402_UNREPLAYABLE_REQUEST');
  }
  if (retryRequest.headers.has(X402_HEADERS.signature) || retryRequest.headers.has(X402_HEADERS.siwx)) {
    throw new X402PaymentError('X402_PAYMENT_HEADER_CONFLICT');
  }

  const intent = typeof options.createAuthorization === 'function'
    ? await options.createAuthorization({
      challenge,
      requirement,
      account,
      chainId: wallet.chainId,
      wallet
    })
    : createEip3009Authorization(requirement, account);
  if (!intent?.authorization || !(intent.typedDataJson || intent.typedData)) {
    throw new X402PaymentError('X402_INVALID_INTENT');
  }

  let signature;
  try {
    const typedData = intent.typedDataJson || intent.typedData;
    signature = options.signer
      ? await options.signer({ typedData, account, chainId: wallet.chainId, requirement })
      : await wallet.signTypedData(typedData);
  } catch (error) {
    throw new X402PaymentError(error?.code === 4001 ? 'X402_WALLET_REJECTED' : 'X402_SIGNING_FAILED');
  }
  if (!signature) {
    throw new X402PaymentError('X402_SIGNING_FAILED');
  }

  const headers = new Headers(retryRequest.headers);
  headers.set(X402_HEADERS.signature, encodeX402Header({
    x402Version: 2,
    resource: challenge.resource,
    accepted: requirement,
    payload: { signature, authorization: intent.authorization }
  }));
  if (siwxProof) {
    headers.set(X402_HEADERS.siwx, encodeX402Header(siwxProof));
  }
  const retry = new Request(retryRequest, { headers });
  const paidResponse = await fetcher(retry);
  const settlementHeader = paidResponse.headers.get(X402_HEADERS.response);
  let settlement;
  if (settlementHeader) {
    settlement = decodeX402Header(settlementHeader);
  }
  if (paidResponse.status === 402) {
    throw new X402PaymentError('X402_PAYMENT_REJECTED', 'X402_PAYMENT_REJECTED', paidResponse);
  }
  if (paidResponse.status === 202 || settlement?.errorReason === 'settlement_pending') {
    notify({ state: 'SETTLEMENT_PENDING', resource: challenge.resource, accepted: requirement, settlement });
    throw new X402PaymentError('X402_SETTLEMENT_UNCONFIRMED', 'X402_SETTLEMENT_UNCONFIRMED', paidResponse);
  }
  if (paidResponse.status === 503 || settlement?.errorReason === 'settlement_unknown') {
    notify({ state: 'UNKNOWN', resource: challenge.resource, accepted: requirement, settlement });
    throw new X402PaymentError('X402_SETTLEMENT_UNCONFIRMED', 'X402_SETTLEMENT_UNCONFIRMED', paidResponse);
  }
  if (settlement && settlement.success !== true) {
    notify({ state: 'UNKNOWN', resource: challenge.resource, accepted: requirement, settlement });
    throw new X402PaymentError('X402_SETTLEMENT_UNCONFIRMED', 'X402_SETTLEMENT_UNCONFIRMED', paidResponse);
  }
  if (paidResponse.ok && !settlement) {
    notify({ state: 'UNKNOWN', resource: challenge.resource, accepted: requirement, settlement });
    throw new X402PaymentError('X402_SETTLEMENT_UNCONFIRMED', 'X402_SETTLEMENT_UNCONFIRMED', paidResponse);
  }
  notify({
    state: settlement?.success ? 'SETTLED' : 'PAYMENT_RESPONSE',
    resource: challenge.resource,
    accepted: requirement,
    settlement
  });
  return paidResponse;
}

async function chooseRequirement(accepts, options) {
  if (accepts.length === 1) {
    return accepts[0];
  }
  if (typeof options.selectPayment !== 'function') {
    throw new X402PaymentError('X402_PAYMENT_SELECTION_REQUIRED');
  }
  return options.selectPayment(accepts);
}

function walletAccount(wallet) {
  if (!wallet || wallet.isConnected === false) {
    return null;
  }
  return typeof wallet.account === 'string' && wallet.account ? wallet.account : null;
}

function chainMatches(chainId, network) {
  const expected = /^eip155:([1-9][0-9]*)$/.exec(network || '');
  if (!expected || chainId == null) {
    return false;
  }
  try {
    return BigInt(chainId) === BigInt(expected[1]);
  } catch {
    return false;
  }
}

async function createReplay(input, init, options) {
  if (typeof options.replayRequest === 'function') {
    const request = await options.replayRequest(input.clone());
    return request instanceof Request ? request : new Request(request);
  }
  if (!isReplayableRequest(input, init)) {
    return null;
  }
  try {
    if (input instanceof Request) {
      return input.clone();
    }
    const base = globalThis.location?.href || 'http://localhost/';
    const url = new URL(String(input), base);
    return new Request(url, init);
  } catch {
    return null;
  }
}

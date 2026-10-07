import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createEip3009Authorization, decodeX402Header, encodeX402Header, isReplayableRequest, x402Fetch }
  from '../../main/resources/META-INF/frontend/x402-fetch.js';

const account = '0x0000000000000000000000000000000000000001';
const requirement = {
  scheme: 'exact', network: 'eip155:31337', amount: '1000000',
  asset: '0x0000000000000000000000000000000000000002',
  payTo: '0x0000000000000000000000000000000000000003',
  maxTimeoutSeconds: 300, extra: {
    name: 'Test Token', version: '1', assetTransferMethod: 'eip3009', paymentFlow: 'authorization'
  }
};
const challenge = {
  x402Version: 2,
  resource: { url: 'https://merchant.test/api/quote', description: '报价 €', mimeType: 'application/json' },
  accepts: [requirement]
};
const intent = {
  typedDataJson: '{"domain":{},"message":{}}',
  authorization: { from: account, to: requirement.payTo, value: requirement.amount }
};
const response = (status, headers = {}, body = 'body') => new Response(body, { status, headers });
const wallet = (overrides = {}) => ({ account, chainId: '0x7a69', signTypedData: vi.fn(async () => '0xsignature'), ...overrides });
const options = overrides => ({ wallet: wallet(), createAuthorization: vi.fn(async () => intent), ...overrides });

describe('x402Fetch headers', () => {
  it('round trips UTF-8 JSON', () => {
    expect(decodeX402Header(encodeX402Header(challenge))).toEqual(challenge);
  });

  it.each([undefined, '', '%%%bad', 'eyJ', 'W10='])('rejects malformed header %s', value => {
    expect(() => decodeX402Header(value)).toThrow();
  });

  it('rejects unsupported protocol versions', () => {
    expect(() => decodeX402Header(encodeX402Header({ x402Version: 1 })))
      .toThrowError(expect.objectContaining({ code: 'X402_UNSUPPORTED_VERSION' }));
  });
});

describe('x402Fetch', () => {
  it('builds server-compatible EIP-712 typed data and a random 32-byte nonce', () => {
    const first = createEip3009Authorization(requirement, account, 1_700_000_000);
    const second = createEip3009Authorization(requirement, account, 1_700_000_000);
    const typedData = JSON.parse(first.typedDataJson);

    expect(first.authorization).toMatchObject({
      from: account,
      to: requirement.payTo,
      value: requirement.amount,
      validAfter: '1699999400',
      validBefore: '1700000300'
    });
    expect(first.authorization.nonce).toMatch(/^0x[0-9a-f]{64}$/);
    expect(second.authorization.nonce).toMatch(/^0x[0-9a-f]{64}$/);
    expect(second.authorization.nonce).not.toBe(first.authorization.nonce);
    expect(typedData).toEqual({
      domain: {
        name: 'Test Token',
        version: '1',
        chainId: '31337',
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
      message: {
        ...first.authorization
      }
    });
  });

  it('constructs and signs authorization from the challenge without a server intent hook', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockImplementationOnce(async request => {
        const payment = decodeX402Header(request.headers.get('PAYMENT-SIGNATURE'));
        expect(payment.payload.authorization).toMatchObject({
          from: account,
          to: requirement.payTo,
          value: requirement.amount
        });
        expect(payment.payload.authorization.nonce).toMatch(/^0x[0-9a-f]{64}$/);
        return response(200, { 'PAYMENT-RESPONSE': encodeX402Header({ success: true }) });
      });
    const connectedWallet = wallet();
    const signer = connectedWallet.signTypedData;

    const result = await x402Fetch('/api/quote', {}, { fetch, wallet: connectedWallet });

    expect(result.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(signer).toHaveBeenCalledTimes(1);
    expect(JSON.parse(signer.mock.calls[0][0])).toMatchObject({
      domain: { name: 'Test Token', version: '1', chainId: '31337', verifyingContract: requirement.asset },
      primaryType: 'TransferWithAuthorization'
    });
  });

  it('returns non-402 responses unchanged after one fetch', async () => {
    const result = response(200);
    const fetch = vi.fn(async () => result);
    const signer = vi.fn();
    await expect(x402Fetch('/api/quote', {}, { fetch, signer })).resolves.toBe(result);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(signer).not.toHaveBeenCalled();
  });

  it('signs one accepted requirement and retries once without mutating init', async () => {
    const headers = new Headers({ 'X-Custom': 'keep' });
    const init = { method: 'POST', headers, body: 'payload' };
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockImplementationOnce(async request => {
        expect(request).toBeInstanceOf(Request);
        expect(request.method).toBe('POST');
        expect(request.headers.get('x-custom')).toBe('keep');
        expect(decodeX402Header(request.headers.get('payment-signature'))).toMatchObject({
          accepted: requirement,
          payload: { signature: '0xsignature', authorization: intent.authorization }
        });
        expect(await request.text()).toBe('payload');
        return response(200, { 'PAYMENT-RESPONSE': encodeX402Header({ success: true }) });
      });
    const signer = vi.fn(async () => '0xsignature');
    const onState = vi.fn();
    const result = await x402Fetch('/api/quote', init, options({ fetch, signer, onState }));
    expect(result.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(signer).toHaveBeenCalledTimes(1);
    expect(headers.has('PAYMENT-SIGNATURE')).toBe(false);
    expect(onState).toHaveBeenLastCalledWith(expect.objectContaining({ state: 'SETTLED' }));
  });

  it('replays the effective Request including init method, headers and body overrides', async () => {
    const source = new Request('https://merchant.test/api/quote', {
      method: 'POST', headers: { 'X-Original': 'original' }, body: 'source body'
    });
    const init = { method: 'PUT', headers: { 'X-Override': 'selected' }, body: 'effective body' };
    const fetch = vi.fn()
      .mockImplementationOnce(async request => {
        expect(request.method).toBe('PUT');
        expect(request.headers.get('x-override')).toBe('selected');
        expect(request.headers.has('x-original')).toBe(false);
        expect(await request.text()).toBe('effective body');
        return response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) });
      })
      .mockImplementationOnce(async request => {
        expect(request.method).toBe('PUT');
        expect(request.headers.get('x-override')).toBe('selected');
        expect(request.headers.has('x-original')).toBe(false);
        expect(decodeX402Header(request.headers.get('PAYMENT-SIGNATURE'))).toBeTruthy();
        expect(await request.text()).toBe('effective body');
        return response(200, { 'PAYMENT-RESPONSE': encodeX402Header({ success: true }) });
      });

    const result = await x402Fetch(source, init, options({ fetch }));

    expect(result.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('requires explicit selection for multiple requirements and does not sign on cancel', async () => {
    const multi = { ...challenge, accepts: [requirement, { ...requirement, network: 'eip155:1' }] };
    const fetch = vi.fn(async () => response(402, { 'PAYMENT-REQUIRED': encodeX402Header(multi) }));
    const signer = vi.fn();
    await expect(x402Fetch('/api/quote', {}, options({ fetch, signer })))
      .rejects.toMatchObject({ code: 'X402_PAYMENT_SELECTION_REQUIRED' });
    await expect(x402Fetch('/api/quote', {}, options({
      fetch, signer, selectPayment: async () => null
    }))).rejects.toMatchObject({ code: 'X402_PAYMENT_CANCELLED' });
    expect(signer).not.toHaveBeenCalled();
  });

  it('uses SIWX provider and sends each proof with its challenge', async () => {
    const item = { ...challenge, extensions: { 'sign-in-with-x': { nonce: 'freshnonce' } } };
    const proof = { message: 'CAIP-122', signature: '0xproof', chainId: 'eip155:31337' };
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(item) }))
      .mockImplementationOnce(async request => {
        expect(decodeX402Header(request.headers.get('SIGN-IN-WITH-X'))).toEqual(proof);
        return response(200, { 'PAYMENT-RESPONSE': encodeX402Header({ success: true }) });
      });
    const siwxProvider = vi.fn(async () => proof);
    await x402Fetch('/api/quote', {}, options({ fetch, siwxProvider }));
    expect(siwxProvider).toHaveBeenCalledWith(expect.objectContaining({
      challenge: item.extensions['sign-in-with-x'], account
    }));
  });

  it('rejects missing required SIWX and provider rejection before payment signing', async () => {
    const item = { ...challenge, extensions: { 'sign-in-with-x': { required: true } } };
    const fetch = vi.fn(async () => response(402, { 'PAYMENT-REQUIRED': encodeX402Header(item) }));
    const signer = vi.fn();
    await expect(x402Fetch('/api/quote', {}, options({ fetch, signer })))
      .rejects.toMatchObject({ code: 'X402_SIWX_REQUIRED' });
    await expect(x402Fetch('/api/quote', {}, options({
      fetch, signer, siwxProvider: async () => { throw new Error('rejected'); }
    }))).rejects.toMatchObject({ code: 'X402_SIWX_REJECTED' });
    expect(signer).not.toHaveBeenCalled();
  });

  it('fails disconnected wallet, wrong chain and 4001 with stable error codes', async () => {
    const fetch = vi.fn(async () => response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }));
    await expect(x402Fetch('/api/quote', {}, options({ fetch, wallet: wallet({ account: '' }) })))
      .rejects.toMatchObject({ code: 'X402_WALLET_NOT_CONNECTED' });
    await expect(x402Fetch('/api/quote', {}, options({ fetch, wallet: wallet({ chainId: '0x1' }) })))
      .rejects.toMatchObject({ code: 'X402_WRONG_NETWORK' });
    await expect(x402Fetch('/api/quote', {}, options({ fetch,
      signer: async () => { throw Object.assign(new Error('rejected'), { code: 4001 }); }
    }))).rejects.toMatchObject({ code: 'X402_WALLET_REJECTED' });
  });

  it('never makes a third fetch when the payment retry is still 402', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }));
    await expect(x402Fetch('/api/quote', {}, options({ fetch })))
      .rejects.toMatchObject({ code: 'X402_PAYMENT_REJECTED' });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('rejects pending settlement with its response attached and reports its state', async () => {
    const pending = response(202, {
      'PAYMENT-RESPONSE': encodeX402Header({ success: false, errorReason: 'settlement_pending', transaction: '0xtx' })
    }, 'pending body');
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockResolvedValueOnce(pending);
    const onState = vi.fn();
    const error = await x402Fetch('/api/quote', {}, options({ fetch, onState }))
      .then(() => null, caught => caught);
    expect(error).toMatchObject({ code: 'X402_SETTLEMENT_UNCONFIRMED', response: pending });
    expect(onState).toHaveBeenLastCalledWith(expect.objectContaining({ state: 'SETTLEMENT_PENDING' }));
    await expect(error.response.text()).resolves.toBe('pending body');
  });

  it('requires a successful settlement header for a successful retry response', async () => {
    for (const paidResponse of [response(200), response(200, {
      'PAYMENT-RESPONSE': encodeX402Header({ success: false, errorReason: 'settlement_unknown' })
    })]) {
      const fetch = vi.fn()
        .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
        .mockResolvedValueOnce(paidResponse);
      const error = await x402Fetch('/api/quote', {}, options({ fetch })).then(() => null, caught => caught);
      expect(error).toMatchObject({ code: 'X402_SETTLEMENT_UNCONFIRMED', response: paidResponse });
    }
  });

  it('reports an unknown settlement response without treating it as settled', async () => {
    const unknown = response(503, {
      'PAYMENT-RESPONSE': encodeX402Header({ success: false, errorReason: 'settlement_unknown' })
    });
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockResolvedValueOnce(unknown);
    const onState = vi.fn();
    const error = await x402Fetch('/api/quote', {}, options({ fetch, onState }))
      .then(() => null, caught => caught);
    expect(error).toMatchObject({ code: 'X402_SETTLEMENT_UNCONFIRMED', response: unknown });
    expect(onState).toHaveBeenLastCalledWith(expect.objectContaining({ state: 'UNKNOWN' }));
  });

  it('rejects stream retries before signer and accepts an explicit replay factory', async () => {
    const stream = new ReadableStream({ start(controller) {
      controller.enqueue(new TextEncoder().encode('first body'));
    } });
    const fetch = vi.fn(async () => response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }));
    const signer = vi.fn();
    await expect(x402Fetch('/api/quote', { method: 'POST', body: stream, duplex: 'half' },
      options({ fetch, signer }))).rejects.toMatchObject({ code: 'X402_UNREPLAYABLE_REQUEST' });
    expect(signer).not.toHaveBeenCalled();
    const replayRequest = vi.fn(async () => new Request('https://merchant.test/api/quote', {
      method: 'POST', body: 'fresh'
    }));
    const replayFetch = vi.fn()
      .mockResolvedValueOnce(response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }))
      .mockResolvedValueOnce(response(200, { 'PAYMENT-RESPONSE': encodeX402Header({ success: true }) }));
    await x402Fetch('https://merchant.test/api/quote', {}, options({
      fetch: replayFetch, replayRequest
    }));
    expect(replayRequest).toHaveBeenCalledTimes(1);
  });

  it('rejects consumed requests, aborts before signing and respects Fetch header casing', async () => {
    const consumed = new Request('https://merchant.test/api/quote', { method: 'POST', body: 'used' });
    await consumed.text();
    expect(isReplayableRequest(consumed)).toBe(false);
    const headers = new Headers({ 'payment-signature': 'existing' });
    const fetch = vi.fn(async () => response(402, { 'PAYMENT-REQUIRED': encodeX402Header(challenge) }));
    await expect(x402Fetch('/api/quote', { headers }, options({ fetch })))
      .rejects.toMatchObject({ code: 'X402_PAYMENT_HEADER_CONFLICT' });
    const controller = new AbortController();
    controller.abort();
    const signer = vi.fn();
    await expect(x402Fetch('/api/quote', { signal: controller.signal }, options({ fetch, signer })))
      .rejects.toMatchObject({ code: 'X402_ABORTED' });
    expect(signer).not.toHaveBeenCalled();
    expect(headers.get('PAYMENT-SIGNATURE')).toBe('existing');
  });
});

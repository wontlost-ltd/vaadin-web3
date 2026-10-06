import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getCapabilities, getCallsStatus, sendCalls, showCallsStatus } from '../../main/resources/META-INF/frontend/web3-calls.js';

const componentFor = (request) => ({ account: '0xabc', provider: { request: vi.fn(request) } });

describe('EIP-5792 provider calls', () => {
  it('uses standard parameters, version and normalized chain IDs', async () => {
    const component = componentFor(() => ({ id: 'batch-1' }));
    await sendCalls(component, { chainId: 1, atomicRequired: false, calls: [{ to: '0xdef', value: '0x0' }] });
    expect(component.provider.request).toHaveBeenCalledWith({ method: 'wallet_sendCalls', params: [{
      version: '2.0.0', chainId: '0x1', atomicRequired: false, calls: [{ to: '0xdef', value: '0x0' }]
    }] });
    await getCapabilities(component, [1, '0x00089']);
    expect(component.provider.request).toHaveBeenLastCalledWith({ method: 'wallet_getCapabilities',
      params: ['0xabc', ['0x1', '0x89']] });
  });

  it('passes through status methods and wallet error code and message', async () => {
    const component = componentFor(({ method }) => {
      if (method === 'wallet_getCallsStatus') return { status: 200 };
      if (method === 'wallet_showCallsStatus') return null;
      throw Object.assign(new Error('wallet rejected'), { code: 4001 });
    });
    await expect(getCallsStatus(component, 'batch-1')).resolves.toEqual({ status: 200 });
    await expect(showCallsStatus(component, 'batch-1')).resolves.toBeNull();
    await expect(sendCalls(component, { chainId: 1, atomicRequired: false, calls: [{ to: '0x1' }] }))
      .rejects.toMatchObject({ code: 4001, message: 'wallet rejected' });
  });

  it('rejects invalid params locally with -32602', async () => {
    const component = componentFor(vi.fn());
    await expect(sendCalls(component, { chainId: 1, atomicRequired: true, calls: [] }))
      .rejects.toMatchObject({ code: -32602 });
    expect(component.provider.request).not.toHaveBeenCalled();
  });

  it('reports a missing provider as 4900 (disconnected), not -32601 which would look like an unsupported method', async () => {
    await expect(sendCalls({ provider: null, account: '0xabc' }, { chainId: 1, atomicRequired: false, calls: [{ to: '0x1' }] }))
      .rejects.toMatchObject({ code: 4900 });
  });
});

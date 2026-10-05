import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Web3Connect } from '../../main/resources/META-INF/frontend/web3-connect.js';

class MockProvider {
  constructor() {
    this.listeners = new Map();
    this.calls = [];
    this.chainId = '0x1';
    this.handlers = {};
  }
  on(name, callback) {
    const listeners = this.listeners.get(name) || new Set();
    listeners.add(callback);
    this.listeners.set(name, listeners);
  }
  removeListener(name, callback) { this.listeners.get(name)?.delete(callback); }
  emit(name, value) { this.listeners.get(name)?.forEach((callback) => callback(value)); }
  async request(args) {
    this.calls.push(args);
    if (this.handlers[args.method]) return this.handlers[args.method](args);
    if (args.method === 'eth_requestAccounts' || args.method === 'eth_accounts') return ['0x1234567890123456789012345678901234567890'];
    if (args.method === 'eth_chainId') return this.chainId;
    if (args.method === 'wallet_revokePermissions') return null;
    return null;
  }
}

const makeConnect = (provider = new MockProvider()) => {
  window.ethereum = provider;
  const element = new Web3Connect();
  document.body.append(element);
  return { element, provider };
};

beforeEach(() => {
  document.body.replaceChildren();
  localStorage.clear();
  delete window.ethereum;
});

describe('web3-connect', () => {
  it('connects and publishes provider availability', async () => {
    const detected = vi.fn();
    document.addEventListener('web3-provider-detected', detected);
    try {
      const { element } = makeConnect();
      await element.connect();
      expect(element.account).toBe('0x1234567890123456789012345678901234567890');
      expect(element.providerAvailable).toBe(true);
      expect(detected).toHaveBeenCalledTimes(1);
      expect(detected.mock.calls[0][0].detail).toEqual({ available: true });
    } finally {
      document.removeEventListener('web3-provider-detected', detected);
    }
  });

  it('does not re-announce provider availability on every connect', async () => {
    const { element } = makeConnect();
    const detected = vi.fn();
    element.addEventListener('web3-provider-detected', detected);
    await element.connect();
    element.disconnect();
    await element.connect();
    expect(detected).not.toHaveBeenCalled();
  });

  it('treats a provider without on() as available and still connects', async () => {
    window.ethereum = { request: async ({ method }) => (method === 'eth_chainId' ? '0x1' : ['0x1234567890123456789012345678901234567890']) };
    const element = new Web3Connect();
    document.body.append(element);
    expect(element.providerAvailable).toBe(true);
    await element.connect();
    expect(element.account).toBe('0x1234567890123456789012345678901234567890');
  });

  it('does not attach incomplete provider listeners and still connects', async () => {
    const provider = new MockProvider();
    const on = vi.fn(provider.on.bind(provider));
    window.ethereum = { request: provider.request.bind(provider), on };
    const element = new Web3Connect();
    document.body.append(element);
    expect(element.providerAvailable).toBe(true);
    await element.connect();
    expect(element.account).toBe('0x1234567890123456789012345678901234567890');
    expect(on).not.toHaveBeenCalled();
  });

  it('encodes UTF-8 for personal_sign', async () => {
    const { element, provider } = makeConnect();
    await element.connect();
    provider.handlers.personal_sign = () => '0xsig';
    await element.signMessage('hé🙂');
    expect(provider.calls.find((call) => call.method === 'personal_sign').params[0]).toBe('0x68c3a9f09f9982');
  });

  it('fills transaction from with the connected account', async () => {
    const { element, provider } = makeConnect();
    await element.connect();
    provider.handlers.eth_sendTransaction = () => '0xhash';
    await element.sendTransaction({ to: '0xabc' });
    expect(provider.calls.find((call) => call.method === 'eth_sendTransaction').params[0].from).toBe(element.account);
  });

  it('dispatches numeric user rejection and rejects', async () => {
    const { element, provider } = makeConnect();
    provider.handlers.eth_requestAccounts = () => { throw Object.assign(new Error('Rejected'), { code: '4001' }); };
    const error = vi.fn();
    element.addEventListener('web3-error', error);
    await expect(element.connect()).rejects.toThrow('Rejected');
    expect(error.mock.calls[0][0].detail.code).toBe(4001);
  });

  it('reports no account and missing provider with standard codes', async () => {
    const { element, provider } = makeConnect();
    const errors = [];
    element.addEventListener('web3-error', (event) => errors.push(event.detail));
    await expect(element.signMessage('x')).rejects.toThrow('No wallet connected');
    expect(errors.at(-1).code).toBe(4100);
    delete window.ethereum;
    await expect(element.connect()).rejects.toThrow('No EIP-1193 provider');
    expect(errors.at(-1).code).toBe(-32601);
    expect(provider).toBeTruthy();
  });

  it('switches to a known chain then reads the provider chain id', async () => {
    const { element, provider } = makeConnect();
    provider.handlers.wallet_switchEthereumChain = () => { provider.chainId = '0x89'; };
    await expect(element.switchChain('0x89')).resolves.toBe('0x89');
    expect(element.chainId).toBe('0x89');
  });

  it('adds an unknown chain and reads chain id after adding', async () => {
    const { element, provider } = makeConnect();
    provider.handlers.wallet_switchEthereumChain = () => { throw Object.assign(new Error('Unknown'), { code: 4902 }); };
    provider.handlers.wallet_addEthereumChain = () => { provider.chainId = '0xa4b1'; };
    await expect(element.switchChain('0xa4b1', { chainName: 'Arbitrum' })).resolves.toBe('0xa4b1');
    expect(provider.calls.some((call) => call.method === 'wallet_addEthereumChain')).toBe(true);
  });

  it('dispatches and rejects when adding an unknown chain fails', async () => {
    const { element, provider } = makeConnect();
    provider.handlers.wallet_switchEthereumChain = () => { throw Object.assign(new Error('Unknown'), { code: 4902 }); };
    provider.handlers.wallet_addEthereumChain = () => { throw Object.assign(new Error('Add failed'), { code: 4001 }); };
    const error = vi.fn();
    element.addEventListener('web3-error', error);
    await expect(element.switchChain('0x2', {})).rejects.toThrow('Add failed');
    expect(error.mock.calls.at(-1)[0].detail.code).toBe(4001);
  });

  it('recognizes a nested mobile 4902 code', async () => {
    const { element, provider } = makeConnect();
    provider.handlers.wallet_switchEthereumChain = () => { throw Object.assign(new Error('Unknown'), { data: { originalError: { code: 4902 } } }); };
    provider.handlers.wallet_addEthereumChain = () => { provider.chainId = '0x2'; };
    await expect(element.switchChain('0x2', {})).resolves.toBe('0x2');
  });

  it('uses a valid nested mobile code when the direct code is unusable', () => {
    const element = new Web3Connect();
    expect(element._errorInfo({ code: 'unknown', data: { originalError: { code: 4902 } }, message: 'Unknown chain' })).toEqual({ code: 4902, message: 'Unknown chain' });
  });

  it('dispatches balance errors and coerces numeric string codes', async () => {
    const { element, provider } = makeConnect();
    await element.connect();
    provider.handlers.eth_getBalance = () => { throw Object.assign(new Error('Balance failed'), { code: '123' }); };
    const error = vi.fn();
    element.addEventListener('web3-error', error);
    await expect(element.getBalance()).rejects.toThrow('Balance failed');
    expect(error.mock.calls[0][0].detail).toEqual({ code: 123, message: 'Balance failed' });
  });

  it('persists disconnect, blocks restore and account auto-connect, then reconnects', async () => {
    const { element, provider } = makeConnect();
    await element.connect();
    element.disconnect();
    expect(localStorage.getItem('web3-connect:disconnected')).toBe('1');
    expect(await element.restore()).toBeNull();
    provider.emit('accountsChanged', ['0xabcdef']);
    expect(element.account).toBe('');
    await element.connect();
    expect(localStorage.getItem('web3-connect:disconnected')).toBeNull();
    expect(element.account).toBe('0x1234567890123456789012345678901234567890');
    expect(provider.calls.some((call) => call.method === 'wallet_revokePermissions')).toBe(true);
  });

  it('synchronizes disconnect between instances on the same page', async () => {
    const provider = new MockProvider();
    const first = makeConnect(provider).element;
    const second = new Web3Connect();
    document.body.append(second);
    const disconnected = vi.fn();
    second.addEventListener('web3-disconnected', disconnected);
    await first.connect();
    await second.connect();

    first.disconnect();

    expect(first.account).toBe('');
    expect(second.account).toBe('');
    expect(disconnected).toHaveBeenCalledTimes(1);
  });

  it('disconnects a connected instance when another tab sets the storage marker', async () => {
    const { element } = makeConnect();
    const disconnected = vi.fn();
    element.addEventListener('web3-disconnected', disconnected);
    await element.connect();

    window.dispatchEvent(new StorageEvent('storage', {
      key: 'web3-connect:disconnected',
      newValue: '1'
    }));

    expect(element.account).toBe('');
    expect(disconnected).toHaveBeenCalledTimes(1);
  });

  it('rejects a pending connect after disconnect without clearing the marker or emitting an error', async () => {
    const { element, provider } = makeConnect();
    let finishRequest;
    provider.handlers.eth_requestAccounts = () => new Promise((resolve) => { finishRequest = resolve; });
    const errors = vi.fn();
    element.addEventListener('web3-error', errors);
    const connecting = element.connect();
    element.disconnect();
    finishRequest(['0x1234567890123456789012345678901234567890']);

    await expect(connecting).rejects.toMatchObject({
      code: 4100,
      message: 'Disconnected while connecting'
    });
    expect(element.account).toBe('');
    expect(localStorage.getItem('web3-connect:disconnected')).toBe('1');
    expect(errors).not.toHaveBeenCalled();
  });

  it('continues to apply account changes while already connected', async () => {
    const { element, provider } = makeConnect();
    await element.connect();
    provider.emit('accountsChanged', ['0xabcdef']);
    expect(element.account).toBe('0xabcdef');
  });

  it('attaches listeners after late provider injection and removes initialization listener on detach', () => {
    const element = new Web3Connect();
    const detected = vi.fn();
    element.addEventListener('web3-provider-detected', detected);
    document.body.append(element);
    const provider = new MockProvider();
    window.ethereum = provider;
    window.dispatchEvent(new Event('ethereum#initialized'));
    expect(element.providerAvailable).toBe(true);
    expect(detected.mock.calls.map(([event]) => event.detail)).toEqual([{ available: false }, { available: true }]);
    const disconnected = vi.fn();
    element.addEventListener('web3-disconnected', disconnected);
    provider.emit('accountsChanged', ['0xabc']);
    provider.emit('accountsChanged', []);
    expect(disconnected).toHaveBeenCalledTimes(1);
    element.remove();
    expect(provider.listeners.get('accountsChanged').size).toBe(0);
  });
});

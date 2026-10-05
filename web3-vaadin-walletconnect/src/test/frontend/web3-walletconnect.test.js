import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Web3Connect } from '../../../../web3-vaadin/src/main/resources/META-INF/frontend/web3-connect.js';
import '../../main/resources/META-INF/frontend/web3-walletconnect.js';

const ACCOUNT = '0x1234567890123456789012345678901234567890';

class MockProvider {
  constructor({ session = null, accounts = [ACCOUNT], failure = null } = {}) {
    this.session = session;
    this.accounts = accounts;
    this.failure = failure;
    this.listeners = new Map();
    this.calls = [];
    this.enable = vi.fn(async () => {
      this.session = { topic: 'session' };
      return this.accounts;
    });
    this.disconnect = vi.fn(async () => { this.session = null; });
  }
  on(event, listener) {
    const listeners = this.listeners.get(event) || new Set();
    listeners.add(listener);
    this.listeners.set(event, listeners);
  }
  removeListener(event, listener) { this.listeners.get(event)?.delete(listener); }
  async request(args) {
    this.calls.push(args);
    if (this.failure) throw this.failure;
    if (args.method === 'eth_accounts' || args.method === 'eth_requestAccounts') return this.accounts;
    if (args.method === 'eth_chainId') return '0xaa36a7';
    return 'ok';
  }
  emit(event, value) { this.listeners.get(event)?.forEach((listener) => listener(value)); }
}

const mount = (projectId = 'project-id') => {
  const element = document.createElement('web3-walletconnect');
  element.projectId = projectId;
  document.body.append(element);
  return element;
};

const inject = (element, provider, initialize = vi.fn(async () => provider)) => {
  element._loadEthereumProvider = vi.fn(async () => ({ EthereumProvider: { init: initialize } }));
  return initialize;
};

beforeEach(() => {
  document.body.replaceChildren();
  localStorage.clear();
});

describe('WalletConnect EIP-6963 provider', () => {
  it('announces immediately with frozen EIP-6963 metadata', () => {
    const announcement = vi.fn();
    window.addEventListener('eip6963:announceProvider', announcement);
    const element = mount();
    window.removeEventListener('eip6963:announceProvider', announcement);
    const { detail } = announcement.mock.calls[0][0];
    expect(detail.info).toMatchObject({ name: 'WalletConnect', rdns: 'com.walletconnect' });
    expect(detail.info.uuid).toBeTruthy();
    expect(detail.info.icon).toMatch(/^data:image\/svg\+xml,/);
    expect(Object.isFrozen(detail)).toBe(true);
    expect(Object.isFrozen(detail.info)).toBe(true);
    expect(detail.provider).toBe(element._provider);
  });

  it('announces again when EIP-6963 requests providers and stops after detach', () => {
    const element = mount();
    const announcement = vi.fn();
    window.addEventListener('eip6963:announceProvider', announcement);
    window.dispatchEvent(new Event('eip6963:requestProvider'));
    element.remove();
    window.dispatchEvent(new Event('eip6963:requestProvider'));
    window.removeEventListener('eip6963:announceProvider', announcement);
    expect(announcement).toHaveBeenCalledTimes(1);
  });

  it('integrates with Web3Connect and connects the selected WalletConnect provider', async () => {
    const walletConnect = mount();
    const provider = new MockProvider();
    inject(walletConnect, provider);
    const connect = new Web3Connect();
    const connected = vi.fn();
    connect.addEventListener('web3-connected', connected);
    document.body.append(connect);
    await expect(connect.connect('com.walletconnect')).resolves.toBe(ACCOUNT);
    expect(provider.enable).toHaveBeenCalledOnce();
    expect(connect.wallets[0].rdns).toBe('com.walletconnect');
    expect(connect.account).toBe(ACCOUNT);
    expect(connect.chainId).toBe('0xaa36a7');
    expect(connected).toHaveBeenCalledOnce();
  });

  it('returns empty accounts without loading when there is no stored session', async () => {
    const element = mount();
    const loader = vi.fn();
    element._loadEthereumProvider = loader;
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([]);
    expect(loader).not.toHaveBeenCalled();
  });

  it('does not load the library for leftover keys or an empty stored session list', async () => {
    localStorage.setItem('wc@2:core:0.3:keychain', '{"k":"v"}');
    localStorage.setItem('wc@2:client:0.3:session', '[]');
    const element = mount();
    const loader = vi.fn();
    element._loadEthereumProvider = loader;
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([]);
    expect(loader).not.toHaveBeenCalled();
  });

  it('reports no accounts instead of failing when the stored session has expired', async () => {
    localStorage.setItem('wc@2:client:0.3:session', '[{"topic":"expired"}]');
    const element = mount();
    // 与真实库一致：无会话时 request 抛 "Please call connect() before request()"
    const provider = new MockProvider({ failure: new Error('Please call connect() before request()') });
    inject(element, provider);
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([]);
    expect(provider.calls).toHaveLength(0);
  });

  it('loads and forwards eth_accounts when a WalletConnect session is stored', async () => {
    localStorage.setItem('wc@2:client:0.3:session', '[{"topic":"session"}]');
    const element = mount();
    const provider = new MockProvider({ session: { topic: 'session' } });
    inject(element, provider);
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([ACCOUNT]);
    expect(provider.calls[0].method).toBe('eth_accounts');
  });

  it('uses enable without a session and request with an existing session', async () => {
    const fresh = mount();
    const first = new MockProvider();
    inject(fresh, first);
    await expect(fresh._provider.request({ method: 'eth_requestAccounts' })).resolves.toEqual([ACCOUNT]);
    expect(first.enable).toHaveBeenCalledOnce();
    expect(first.calls).toHaveLength(0);

    const resumed = mount();
    const second = new MockProvider({ session: { topic: 'session' } });
    inject(resumed, second);
    await expect(resumed._provider.request({ method: 'eth_requestAccounts' })).resolves.toEqual([ACCOUNT]);
    expect(second.enable).not.toHaveBeenCalled();
    expect(second.calls[0].method).toBe('eth_requestAccounts');
  });

  it('initializes only once for concurrent requests and retries after initialization failure', async () => {
    const element = mount();
    const provider = new MockProvider({ session: { topic: 'session' } });
    const initialize = vi.fn(async () => provider);
    inject(element, provider, initialize);
    await Promise.all([
      element._provider.request({ method: 'eth_chainId' }),
      element._provider.request({ method: 'eth_accounts' })
    ]);
    expect(initialize).toHaveBeenCalledOnce();

    const retry = mount();
    const recovered = new MockProvider({ session: { topic: 'session' } });
    const retryInit = vi.fn().mockRejectedValueOnce(new Error('temporary')).mockResolvedValueOnce(recovered);
    inject(retry, recovered, retryInit);
    await expect(retry._provider.request({ method: 'eth_chainId' })).rejects.toMatchObject({ message: 'temporary' });
    await expect(retry._provider.request({ method: 'eth_chainId' })).resolves.toBe('0xaa36a7');
    expect(retryInit).toHaveBeenCalledTimes(2);
  });

  it('attaches listeners registered before initialization and removes them', async () => {
    const element = mount();
    const provider = new MockProvider({ session: { topic: 'session' } });
    inject(element, provider);
    const listener = vi.fn();
    expect(element._provider.on('chainChanged', listener)).toBe(element._provider);
    await element._provider.request({ method: 'eth_chainId' });
    provider.emit('chainChanged', '0xaa36a7');
    expect(listener).toHaveBeenCalledWith('0xaa36a7');
    expect(element._provider.removeListener('chainChanged', listener)).toBe(element._provider);
    provider.emit('chainChanged', '0x1');
    expect(listener).toHaveBeenCalledOnce();
  });

  it.each([
    [new Error('Connection request reset. Please try again.'), 4001],
    [Object.assign(new Error('User rejected'), { code: 5000 }), 4001],
    [Object.assign(new Error('User rejected'), { code: 5001 }), 4001],
    [Object.assign(new Error('User rejected'), { code: 5002 }), 4001],
    [Object.assign(new Error('Provider failure'), { code: -32000 }), -32000]
  ])('normalizes errors while preserving other provider codes', async (failure, code) => {
    const element = mount();
    inject(element, new MockProvider({ session: { topic: 'session' }, failure }));
    await expect(element._provider.request({ method: 'eth_chainId' })).rejects.toMatchObject({ code, message: failure.message });
  });

  it('rejects other methods before connection and rejects missing project IDs', async () => {
    const disconnected = mount();
    const provider = new MockProvider();
    inject(disconnected, provider);
    await expect(disconnected._provider.request({ method: 'eth_chainId' })).rejects.toMatchObject({
      code: 4100,
      message: 'WalletConnect is not connected'
    });

    const unconfigured = mount(' ');
    await expect(unconfigured._provider.request({ method: 'eth_chainId' })).rejects.toMatchObject({
      code: 4900,
      message: 'WalletConnect projectId is not configured'
    });
  });

  it('disconnects the active WalletConnect session when permissions are revoked', async () => {
    const uninitialized = mount();
    uninitialized._loadEthereumProvider = vi.fn();
    await expect(uninitialized._provider.request({ method: 'wallet_revokePermissions' })).resolves.toBeNull();
    expect(uninitialized._loadEthereumProvider).not.toHaveBeenCalled();

    const element = mount();
    const provider = new MockProvider({ session: { topic: 'session' } });
    inject(element, provider);
    await element._provider.request({ method: 'eth_chainId' });
    await expect(element._provider.request({ method: 'wallet_revokePermissions' })).resolves.toBeNull();
    expect(provider.disconnect).toHaveBeenCalledOnce();
  });

  it('waits for a concurrent enable request before revoking the session', async () => {
    const element = mount();
    let finishInitialization;
    const provider = new MockProvider();
    inject(element, provider, () => new Promise((resolve) => { finishInitialization = () => resolve(provider); }));
    const connecting = element._provider.request({ method: 'eth_requestAccounts' });
    const revoking = element._provider.request({ method: 'wallet_revokePermissions' });
    await vi.waitFor(() => expect(finishInitialization).toBeTypeOf('function'));
    finishInitialization();
    await expect(connecting).resolves.toEqual([ACCOUNT]);
    await expect(revoking).resolves.toBeNull();
    expect(provider.disconnect).toHaveBeenCalledOnce();
    expect(provider.session).toBeNull();
  });

  it('passes chains, RPC map, metadata defaults and modal theme to EthereumProvider.init', async () => {
    const element = mount();
    element.chains = [11155111, 84532];
    element.rpcMap = { 11155111: 'https://sepolia.example' };
    element.themeMode = 'dark';
    const provider = new MockProvider({ session: { topic: 'session' } });
    const initialize = inject(element, provider);
    await element._provider.request({ method: 'eth_chainId' });
    expect(initialize).toHaveBeenCalledWith({
      projectId: 'project-id',
      optionalChains: [11155111, 84532],
      showQrModal: true,
      metadata: { name: document.title || location.host, description: '', url: location.origin, icons: [] },
      rpcMap: { 11155111: 'https://sepolia.example' },
      qrModalOptions: { themeMode: 'dark' }
    });
  });
});

describe('stored session detection (IndexedDB, as used by WalletConnect 2.x)', () => {
  const DB = 'WALLET_CONNECT_V2_INDEXED_DB';
  let fake;

  beforeEach(async () => {
    fake = await import('fake-indexeddb');
    globalThis.indexedDB = new fake.IDBFactory();
  });

  afterEach(() => {
    delete globalThis.indexedDB;
  });

  // 按 WalletConnect keyvaluestorage 的方式建库建表并写入（值为 JSON 字符串）
  const seed = (entries) => new Promise((resolve, reject) => {
    const request = indexedDB.open(DB);
    request.onupgradeneeded = () => request.result.createObjectStore('keyvaluestorage');
    request.onsuccess = () => {
      const db = request.result;
      const tx = db.transaction('keyvaluestorage', 'readwrite');
      for (const [key, value] of Object.entries(entries)) tx.objectStore('keyvaluestorage').put(value, key);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => reject(tx.error);
    };
    request.onerror = () => reject(request.error);
  });

  it('loads and restores when IndexedDB holds a session', async () => {
    await seed({ 'wc@2:core:0.3:keychain': '{}', 'wc@2:client:0.3:session': '[{"topic":"t"}]' });
    const element = mount();
    const provider = new MockProvider({ session: { topic: 't' } });
    inject(element, provider);
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([ACCOUNT]);
  });

  it('stays unloaded when IndexedDB only holds pairing data or an empty session list', async () => {
    await seed({ 'wc@2:core:0.3:pairing': '[{"topic":"p"}]', 'wc@2:client:0.3:session': '[]' });
    const element = mount();
    const loader = vi.fn();
    element._loadEthereumProvider = loader;
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([]);
    expect(loader).not.toHaveBeenCalled();
  });

  it('never creates the WalletConnect database when it does not exist', async () => {
    const element = mount();
    element._loadEthereumProvider = vi.fn();
    await expect(element._provider.request({ method: 'eth_accounts' })).resolves.toEqual([]);
    expect((await indexedDB.databases()).map((db) => db.name)).not.toContain(DB);
  });

  it('loads the library when the browser cannot list databases', async () => {
    indexedDB.databases = undefined;
    const element = mount();
    const provider = new MockProvider({ session: { topic: 't' } });
    const initialize = inject(element, provider);
    await element._provider.request({ method: 'eth_accounts' });
    expect(initialize).toHaveBeenCalledOnce();
  });
});

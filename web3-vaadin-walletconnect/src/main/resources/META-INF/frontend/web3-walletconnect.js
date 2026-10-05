const DEFAULT_ICON = 'data:image/svg+xml,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect x="14" y="5" width="36" height="54" rx="6" fill="#667085"/><rect x="18" y="11" width="28" height="38" rx="2" fill="#fff"/><path d="M24 19h4v4h-4zm6 0h4v4h-4zm6 0h4v4h-4zM24 25h4v4h-4zm12 0h4v4h-4zM24 31h4v4h-4zm6 0h4v4h-4zm6 0h4v4h-4z" fill="#344054"/><circle cx="32" cy="54" r="2" fill="#fff"/></svg>'
);

const WC_DATABASE = 'WALLET_CONNECT_V2_INDEXED_DB';
const WC_STORE = 'keyvaluestorage';
const SESSION_KEY = /^wc@2:client:[^:]+:session$/;

/** 会话值为 JSON 数组（IndexedDB 中可能是字符串或已解析的对象）。 */
function hasSessions(value) {
  try {
    const sessions = typeof value === 'string' ? JSON.parse(value) : value;
    return Array.isArray(sessions) && sessions.length > 0;
  } catch (error) {
    return false;
  }
}

class Web3WalletConnect extends HTMLElement {
  constructor() {
    super();
    this._uuid = globalThis.crypto?.randomUUID?.() || `walletconnect-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    this._listeners = new Map();
    this._ethereumProviderPromise = null;
    this._ethereumProvider = null;
    this._enablePromise = null;
    this._provider = {
      request: (args) => this._request(args),
      on: (event, listener) => this._on(event, listener),
      removeListener: (event, listener) => this._removeListener(event, listener)
    };
    this._onRequestProvider = () => this._announce();
    this._loadEthereumProvider = () => import('@walletconnect/ethereum-provider');
  }

  connectedCallback() {
    window.addEventListener('eip6963:requestProvider', this._onRequestProvider);
    this._announce();
  }

  disconnectedCallback() {
    window.removeEventListener('eip6963:requestProvider', this._onRequestProvider);
  }

  _announce() {
    const info = Object.freeze({
      uuid: this._uuid,
      name: this.walletName || 'WalletConnect',
      icon: this.walletIcon || DEFAULT_ICON,
      rdns: 'com.walletconnect'
    });
    window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
      detail: Object.freeze({ info, provider: this._provider })
    }));
  }

  async _request({ method, params }) {
    if (method === 'eth_accounts' && !this._ethereumProviderPromise && !(await this._hasStoredSession())) return [];
    if (method === 'wallet_revokePermissions') {
      if (!this._ethereumProviderPromise) return null;
      let provider;
      try {
        provider = await this._ethereumProviderPromise;
      } catch (error) {
        return null;
      }
      if (this._enablePromise) await this._enablePromise.catch(() => {});
      try {
        await provider.disconnect();
      } catch (error) {
        throw this._normalizeError(error);
      }
      return null;
    }
    const provider = await this._initialize();
    try {
      if (method === 'eth_requestAccounts' && !provider.session) {
        if (!this._enablePromise) this._enablePromise = provider.enable();
        const enablePromise = this._enablePromise;
        try {
          await enablePromise;
        } finally {
          if (this._enablePromise === enablePromise) this._enablePromise = null;
        }
        return provider.accounts;
      }
      // 会话过期或已断开时 WalletConnect 的 request 会抛 "Please call connect()"；按 EIP-1193 语义未授权即无账户
      if (method === 'eth_accounts' && !provider.session) return [];
      if (method !== 'eth_requestAccounts' && !provider.session) {
        throw this._error(4100, 'WalletConnect is not connected');
      }
      return await provider.request({ method, params });
    } catch (error) {
      throw this._normalizeError(error);
    }
  }

  _initialize() {
    if (!this.projectId || !this.projectId.trim()) {
      return Promise.reject(this._error(4900, 'WalletConnect projectId is not configured'));
    }
    if (!this._ethereumProviderPromise) {
      this._ethereumProviderPromise = this._createProvider().catch((error) => {
        this._ethereumProviderPromise = null;
        this._ethereumProvider = null;
        throw this._normalizeError(error);
      });
    }
    return this._ethereumProviderPromise;
  }

  async _createProvider() {
    const { EthereumProvider } = await this._loadEthereumProvider();
    const chains = Array.isArray(this.chains) && this.chains.length ? this.chains : [1];
    const metadata = this.metadata || {
      name: document.title || location.host,
      description: '',
      url: location.origin,
      icons: []
    };
    const options = {
      projectId: this.projectId,
      optionalChains: [...chains],
      showQrModal: true,
      metadata,
      qrModalOptions: { themeMode: this.themeMode || 'light' }
    };
    if (this.rpcMap && Object.keys(this.rpcMap).length) options.rpcMap = this.rpcMap;
    const provider = await EthereumProvider.init(options);
    this._ethereumProvider = provider;
    for (const [event, listeners] of this._listeners) {
      for (const listener of listeners) provider.on(event, listener);
    }
    return provider;
  }

  /**
   * 只有存在已保存的会话时才值得加载库并连接中继。WalletConnect 2.x 在浏览器中把数据存进
   * IndexedDB（库 WALLET_CONNECT_V2_INDEXED_DB / 表 keyvaluestorage），IndexedDB 不可用时退回 localStorage；
   * 会话键形如 wc@2:client:0.3:session，值为 JSON 数组。无法判断时返回 true（宁可多加载，不丢会话）。
   */
  async _hasStoredSession() {
    try {
      const fromIndexedDb = await this._readIndexedDbSessions();
      if (fromIndexedDb !== null) return fromIndexedDb || this._hasLocalStorageSession();
      return true;
    } catch (error) {
      return true;
    }
  }

  _hasLocalStorageSession() {
    try {
      return Object.keys(localStorage).some((key) => SESSION_KEY.test(key) && hasSessions(localStorage.getItem(key)));
    } catch (error) {
      return false;
    }
  }

  /** 返回 true/false 表示是否有会话；返回 null 表示无法判断。绝不创建数据库（否则会破坏库自身的建表）。 */
  async _readIndexedDbSessions() {
    if (typeof indexedDB === 'undefined') return false;
    if (typeof indexedDB.databases !== 'function') return null;
    const databases = await indexedDB.databases();
    if (!databases.some((db) => db.name === WC_DATABASE)) return false;
    return new Promise((resolve) => {
      // blocked 之后请求仍可能成功：已给出结论时只需关闭连接，避免占用数据库
      let settled = false;
      const settle = (value) => { settled = true; resolve(value); };
      const request = indexedDB.open(WC_DATABASE);
      request.onupgradeneeded = () => request.transaction.abort();
      request.onerror = () => settle(null);
      request.onblocked = () => settle(null);
      request.onsuccess = () => {
        const db = request.result;
        if (settled) { db.close(); return; }
        if (!db.objectStoreNames.contains(WC_STORE)) { db.close(); resolve(false); return; }
        const store = db.transaction(WC_STORE, 'readonly').objectStore(WC_STORE);
        const keys = store.getAllKeys();
        keys.onerror = () => { db.close(); resolve(null); };
        keys.onsuccess = () => {
          const sessionKeys = keys.result.filter((key) => typeof key === 'string' && SESSION_KEY.test(key));
          if (!sessionKeys.length) { db.close(); resolve(false); return; }
          let pending = sessionKeys.length;
          let found = false;
          for (const key of sessionKeys) {
            const value = store.get(key);
            value.onsuccess = () => {
              found = found || hasSessions(value.result);
              if (--pending === 0) { db.close(); resolve(found); }
            };
            value.onerror = () => {
              if (--pending === 0) { db.close(); resolve(found); }
            };
          }
        };
      };
    });
  }

  _on(event, listener) {
    const listeners = this._listeners.get(event) || new Set();
    if (!listeners.has(listener)) {
      listeners.add(listener);
      this._listeners.set(event, listeners);
      if (this._ethereumProvider) this._ethereumProvider.on(event, listener);
    }
    return this._provider;
  }

  _removeListener(event, listener) {
    this._listeners.get(event)?.delete(listener);
    this._ethereumProvider?.removeListener(event, listener);
    return this._provider;
  }

  _normalizeError(error) {
    const message = error?.message || String(error);
    const code = Number(error?.code);
    if (message.includes('Connection request reset') || [5000, 5001, 5002].includes(code)) {
      return this._error(4001, message);
    }
    return this._error(error?.code ?? -1, message);
  }

  _error(code, message) {
    return Object.assign(new Error(message), { code });
  }
}

if (!customElements.get('web3-walletconnect')) {
  customElements.define('web3-walletconnect', Web3WalletConnect);
}

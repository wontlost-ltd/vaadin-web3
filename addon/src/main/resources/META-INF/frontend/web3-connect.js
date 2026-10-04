import { LitElement, html, css } from 'lit';

/**
 * `<web3-connect>` — Vaadin web3 wallet connector.
 *
 * Talks to any EIP-1193 provider injected at `window.ethereum`
 * (MetaMask, Coinbase Wallet, Brave, Rabby, ...). All heavy lifting is
 * plain provider RPC — no bundled web3 library required.
 */
export class Web3Connect extends LitElement {
  static get is() {
    return 'web3-connect';
  }

  static get properties() {
    return {
      account: { type: String, reflect: true },
      chainId: { type: String, reflect: true, attribute: 'chain-id' },
      connectText: { type: String, attribute: 'connect-text' },
      disconnectText: { type: String, attribute: 'disconnect-text' },
      hideButton: { type: Boolean, attribute: 'hide-button' },
      providerAvailable: { type: Boolean },
      wallets: { type: Array },
      selectedWallet: { type: String, attribute: 'selected-wallet' },
      preferredWallet: { type: String, attribute: 'preferred-wallet' },
      _busy: { state: true },
      _pickerOpen: { state: true }
    };
  }

  static get styles() {
    return css`
      :host {
        display: inline-block;
      }
      :host([hidden]) {
        display: none !important;
      }
      button {
        font: inherit;
        cursor: pointer;
        border-radius: var(--lumo-border-radius-m, 0.5em);
        border: none;
        padding: 0.5em 1em;
        color: var(--lumo-primary-contrast-color, #fff);
        background-color: var(--lumo-primary-color, #1676f3);
      }
      button[disabled] {
        opacity: 0.5;
        cursor: default;
      }
      .account {
        font-family: var(--lumo-font-family, monospace);
        margin-inline-start: 0.5em;
      }
      .wallet-picker {
        position: fixed;
        inset: 0;
        z-index: 10000;
        display: grid;
        place-items: center;
        background: var(--lumo-shade-30pct, rgb(0 0 0 / 30%));
      }
      .wallet-picker-content {
        min-width: 18rem;
        max-width: calc(100vw - 2rem);
        padding: 1rem;
        border-radius: var(--lumo-border-radius-l, 0.75rem);
        color: var(--lumo-body-text-color, #222);
        background: var(--lumo-base-color, #fff);
        box-shadow: var(--lumo-box-shadow-l, 0 8px 24px rgb(0 0 0 / 20%));
      }
      .wallet-option {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        width: 100%;
        margin-block: 0.5rem;
        color: var(--lumo-body-text-color, #222);
        background: var(--lumo-contrast-5pct, #f5f5f5);
      }
      .wallet-option img { width: 2rem; height: 2rem; object-fit: contain; }
    `;
  }

  constructor() {
    super();
    this.account = '';
    this.chainId = '';
    this.connectText = 'Connect Wallet';
    this.disconnectText = 'Disconnect';
    this.hideButton = false;
    this.providerAvailable = false;
    this.wallets = [];
    this.selectedWallet = '';
    this.preferredWallet = '';
    this._walletProviders = new Map();
    this._lastWalletInfo = new Map();
    this._pickerResolver = null;
    this._pickerOpen = false;
    this._busy = false;
    this._listeningProvider = null;
    this._providerAnnounced = false;
    this._connectionGeneration = 0;
    this._broadcastingDisconnectState = false;
    this._onDisconnectState = (event) => {
      if (this._broadcastingDisconnectState) return;
      this._handleDisconnectState(event.detail && event.detail.disconnected);
    };
    this._onStorage = (event) => {
      if (event.key === 'web3-connect:disconnected') {
        this._handleDisconnectState(event.newValue === '1');
      }
    };
    this._onAccountsChanged = (accounts) => {
      if (this._isDisconnected() && !this.account && accounts && accounts.length) return;
      this._applyAccounts(accounts);
    };
    this._onChainChanged = (chainId) => {
      this.chainId = chainId || '';
      this._notify('web3-chain-changed', { chainId: this.chainId });
    };
    this._onDisconnect = () => {
      this._applyAccounts([]);
    };
    this._onProviderInitialized = () => {
      this._attachProvider(this.provider);
    };
    this._onAnnounceProvider = (event) => this._handleWalletAnnouncement(event);
    this._onPickerKeydown = (event) => {
      if (event.key === 'Escape' && this._pickerResolver) this._closeWalletPicker();
    };
  }

  get provider() {
    if (this.selectedWallet && this._walletProviders.has(this.selectedWallet)) {
      return this._walletProviders.get(this.selectedWallet).provider;
    }
    if (this.wallets.length === 1) return this._walletProviders.get(this.wallets[0].rdns)?.provider;
    if (this.preferredWallet && this._walletProviders.has(this.preferredWallet)) {
      return this._walletProviders.get(this.preferredWallet).provider;
    }
    return typeof window !== 'undefined' ? window.ethereum : undefined;
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('web3-connect:disconnect-state', this._onDisconnectState);
    window.addEventListener('storage', this._onStorage);
    window.addEventListener('eip6963:announceProvider', this._onAnnounceProvider);
    window.dispatchEvent(new Event('eip6963:requestProvider'));
    this._attachProvider(this.provider);
    if (!this.provider) window.addEventListener('ethereum#initialized', this._onProviderInitialized, { once: true });
  }

  disconnectedCallback() {
    window.removeEventListener('ethereum#initialized', this._onProviderInitialized);
    window.removeEventListener('web3-connect:disconnect-state', this._onDisconnectState);
    window.removeEventListener('storage', this._onStorage);
    window.removeEventListener('eip6963:announceProvider', this._onAnnounceProvider);
    window.removeEventListener('keydown', this._onPickerKeydown);
    this._closeWalletPicker(false);
    this._detachProvider();
    // 元素重新挂载时应再通知一次服务端
    this._providerAnnounced = false;
    super.disconnectedCallback();
  }

  render() {
    return html`
      ${this.hideButton ? '' : html`<button part="button" ?disabled=${this._busy} @click=${this._toggle}>
        ${this.account ? this.disconnectText : this.connectText}
      </button>`}
      ${!this.hideButton && this.account
        ? html`<span part="account" class="account">${this._short(this.account)}</span>`
        : ''}
      ${this._pickerOpen ? html`
        <div class="wallet-picker" part="wallet-picker" @click=${(event) => {
          if (event.target === event.currentTarget) this._closeWalletPicker();
        }}>
          <div class="wallet-picker-content" role="dialog" aria-modal="true" aria-label="Choose a wallet">
            <h2>Choose a wallet</h2>
            ${this.wallets.map((wallet) => html`
              <button class="wallet-option" part="wallet-option" @click=${() => this._chooseWallet(wallet.rdns)}>
                ${typeof wallet.icon === 'string' && wallet.icon.startsWith('data:image/')
                  ? html`<img src=${wallet.icon} alt="">`
                  : ''}
                <span>${wallet.name}</span>
              </button>
            `)}
          </div>
        </div>` : ''}
    `;
  }

  _short(addr) {
    return addr.length > 10 ? `${addr.slice(0, 6)}…${addr.slice(-4)}` : addr;
  }

  async _toggle() {
    if (this.account) {
      this.disconnect();
    } else {
      try {
        await this.connect();
      } catch (e) {
        // error already dispatched in connect()
      }
    }
  }

  /** Requests wallet connection. Resolves to the selected account or rejects. */
  async connect(rdns) {
    if (rdns) this.selectedWallet = rdns;
    else if (!this.selectedWallet) {
      const lastWallet = this._getPreferredRdns();
      if (lastWallet && this._walletProviders.has(lastWallet)) this.selectedWallet = lastWallet;
    }
    const preferred = this._getPreferredRdns();
    if (!rdns && !this.selectedWallet && this.wallets.length > 1 &&
        (!preferred || !this._walletProviders.has(preferred))) {
      try { this.selectedWallet = await this._showWalletPicker(); }
      catch (e) { this._error(e); throw e; }
    }
    const p = this._requireProvider();
    this._attachProvider(p);
    this._busy = true;
    const generation = this._connectionGeneration;
    try {
      const accounts = await p.request({ method: 'eth_requestAccounts' });
      this._ensureConnectionGeneration(generation);
      this.chainId = await p.request({ method: 'eth_chainId' });
      this._ensureConnectionGeneration(generation);
      this._setDisconnected(false);
      this._applyAccounts(accounts);
      const connectedWallet = this.wallets.find((wallet) => this._walletProviders.get(wallet.rdns)?.provider === p);
      if (connectedWallet) {
        try { localStorage.setItem('web3-connect:wallet', connectedWallet.rdns); } catch (e) { /* 存储不可用时仍可连接。 */ }
      }
      return this.account;
    } catch (e) {
      if (generation !== this._connectionGeneration) {
        throw this._disconnectedWhileConnecting();
      }
      this._error(e);
      throw e;
    } finally {
      this._busy = false;
    }
  }

  /** Forgets the connected account app-side (EIP-1193 has no real disconnect). */
  disconnect() {
    this._connectionGeneration += 1;
    this._setDisconnected(true);
    const p = this.provider;
    if (p && p.request) {
      try { Promise.resolve(p.request({ method: 'wallet_revokePermissions', params: [{ eth_accounts: {} }] })).catch(() => {}); }
      catch (e) { /* 钱包不支持撤销授权时忽略。 */ }
    }
    this._applyAccounts([]);
  }

  /** Silently restores an already-authorized connection, if any. */
  async restore() {
    if (this._isDisconnected()) return null;
    let p = this.provider;
    let lastRdns = '';
    try { lastRdns = localStorage.getItem('web3-connect:wallet') || ''; } catch (e) { /* 忽略不可用的存储。 */ }
    if (lastRdns && !this._walletProviders.has(lastRdns)) await this._waitForWallet(lastRdns, 300);
    if (lastRdns && this._walletProviders.has(lastRdns)) {
      this.selectedWallet = lastRdns;
      p = this._walletProviders.get(lastRdns).provider;
    }
    if (!p) {
      return null;
    }
    try {
      const accounts = await p.request({ method: 'eth_accounts' });
      if (accounts && accounts.length) {
        this.chainId = await p.request({ method: 'eth_chainId' });
        this._applyAccounts(accounts);
      }
      return this.account || null;
    } catch (e) {
      this._error(e);
      return null;
    }
  }

  _getPreferredRdns() {
    if (this.preferredWallet) return this.preferredWallet;
    try { return localStorage.getItem('web3-connect:wallet') || ''; } catch (e) { return ''; }
  }

  _handleWalletAnnouncement(event) {
    const detail = event.detail;
    const info = detail && detail.info;
    if (!info || !info.uuid || !info.rdns || !detail.provider) return;
    const sameAnnouncement = [...this._walletProviders.values()].some((wallet) =>
      wallet.uuid === info.uuid && wallet.rdns === info.rdns);
    if (sameAnnouncement) return;
    const existing = this._walletProviders.get(info.rdns);
    if (existing) this._walletProviders.delete(info.rdns);
    for (const [rdns, wallet] of [...this._walletProviders]) {
      if (wallet.uuid === info.uuid) this._walletProviders.delete(rdns);
    }
    this._walletProviders.set(info.rdns, { uuid: info.uuid, rdns: info.rdns, provider: detail.provider });
    this._lastWalletInfo.set(info.uuid, info);
    this.wallets = [...this._walletProviders].map(([rdns, wallet]) => {
      const announced = this._lastWalletInfo.get(wallet.uuid) || info;
      return { uuid: wallet.uuid, name: announced.name || rdns, icon: announced.icon || '', rdns };
    });
    this._notify('web3-wallets-changed', { wallets: this.wallets });
    this._attachProvider(this.provider);
    this._resolveWalletWaiters(info.rdns);
  }

  _showWalletPicker() {
    this._pickerOpen = true;
    window.addEventListener('keydown', this._onPickerKeydown);
    return new Promise((resolve, reject) => { this._pickerResolver = { resolve, reject }; });
  }

  _chooseWallet(rdns) {
    const resolver = this._pickerResolver;
    if (!resolver) return;
    this.selectedWallet = rdns;
    this._pickerResolver = null;
    this._pickerOpen = false;
    window.removeEventListener('keydown', this._onPickerKeydown);
    resolver.resolve(rdns);
  }

  _closeWalletPicker(dispatchError = true) {
    const resolver = this._pickerResolver;
    if (!resolver) return;
    this._pickerResolver = null;
    this._pickerOpen = false;
    window.removeEventListener('keydown', this._onPickerKeydown);
    const error = Object.assign(new Error('User closed the wallet picker'), { code: 4001 });
    resolver.reject(error);
    if (dispatchError) this._error(error);
  }

  _waitForWallet(rdns, timeout) {
    if (this._walletProviders.has(rdns)) return Promise.resolve();
    return new Promise((resolve) => {
      const waiter = { rdns, resolve };
      this._walletWaiters ||= [];
      this._walletWaiters.push(waiter);
      setTimeout(() => {
        this._walletWaiters = this._walletWaiters.filter((item) => item !== waiter);
        resolve();
      }, timeout);
    });
  }

  _resolveWalletWaiters(rdns) {
    for (const waiter of [...(this._walletWaiters || [])]) {
      if (waiter.rdns === rdns) {
        this._walletWaiters = this._walletWaiters.filter((item) => item !== waiter);
        waiter.resolve();
      }
    }
  }

  /** personal_sign over the connected account. Resolves to the signature. */
  async signMessage(message) {
    const p = this._requireProvider();
    this._requireAccount();
    try {
      const hex = '0x' + Array.from(new TextEncoder().encode(message))
        .map((b) => b.toString(16).padStart(2, '0')).join('');
      const signature = await p.request({
        method: 'personal_sign',
        params: [hex, this.account]
      });
      this._notify('web3-message-signed', { message, signature, account: this.account });
      return signature;
    } catch (e) {
      this._error(e);
      throw e;
    }
  }

  /** eth_signTypedData_v4. `typedDataJson` is the EIP-712 payload as a JSON string. */
  async signTypedData(typedDataJson) {
    const p = this._requireProvider();
    this._requireAccount();
    try {
      const signature = await p.request({
        method: 'eth_signTypedData_v4',
        params: [this.account, typedDataJson]
      });
      this._notify('web3-message-signed', { message: typedDataJson, signature, account: this.account });
      return signature;
    } catch (e) {
      this._error(e);
      throw e;
    }
  }

  /**
   * Sends a transaction. `tx` is an object with `to`, and optionally
   * `value` (hex wei), `data`, `gas`, etc. Resolves to the tx hash.
   */
  async sendTransaction(tx) {
    const p = this._requireProvider();
    this._requireAccount();
    try {
      const hash = await p.request({
        method: 'eth_sendTransaction',
        params: [{ from: this.account, ...tx }]
      });
      this._notify('web3-transaction-sent', { hash, account: this.account });
      return hash;
    } catch (e) {
      this._error(e);
      throw e;
    }
  }

  /** Balance of the connected account in wei, as a hex string. */
  async getBalance() {
    const p = this._requireProvider();
    this._requireAccount();
    try {
      return await p.request({ method: 'eth_getBalance', params: [this.account, 'latest'] });
    } catch (e) {
      this._error(e);
      throw e;
    }
  }

  /**
   * Switches the wallet to the given chain (hex id, e.g. "0x1"). If the
   * chain is unknown to the wallet and `addChainParams` is given, it is
   * added via wallet_addEthereumChain and switched to.
   */
  async switchChain(chainIdHex, addChainParams) {
    const p = this._requireProvider();
    try {
      await p.request({
        method: 'wallet_switchEthereumChain',
        params: [{ chainId: chainIdHex }]
      });
    } catch (e) {
      if (this._errorInfo(e).code === 4902 && addChainParams) {
        try {
          await p.request({ method: 'wallet_addEthereumChain', params: [{ ...addChainParams, chainId: chainIdHex }] });
        } catch (addError) {
          this._error(addError);
          throw addError;
        }
      } else {
        this._error(e);
        throw e;
      }
    }
    this.chainId = await p.request({ method: 'eth_chainId' });
    return this.chainId;
  }

  _applyAccounts(accounts) {
    const previous = this.account;
    this.account = accounts && accounts.length ? accounts[0] : '';
    if (this.account && this.account !== previous) {
      this._notify('web3-connected', { account: this.account, chainId: this.chainId });
    } else if (!this.account && previous) {
      this._notify('web3-disconnected', { previousAccount: previous });
    }
  }

  _requireProvider() {
    const p = this.provider;
    if (!p) {
      const error = new Error('No EIP-1193 provider found (is a wallet extension installed?)');
      error.code = -32601;
      this._error(error);
      throw error;
    }
    return p;
  }

  _requireAccount() {
    if (!this.account) {
      const error = new Error('No wallet connected');
      error.code = 4100;
      this._error(error);
      throw error;
    }
  }

  _errorInfo(e) {
    const directCode = e && e.code;
    const directNumber = typeof directCode === 'number' ? directCode
      : typeof directCode === 'string' && /^[-+]?\d+$/.test(directCode) ? Number(directCode) : NaN;
    const nestedCode = e && e.data && e.data.originalError && e.data.originalError.code;
    const nestedNumber = typeof nestedCode === 'number' ? nestedCode
      : typeof nestedCode === 'string' && /^[-+]?\d+$/.test(nestedCode) ? Number(nestedCode) : NaN;
    const code = Number.isFinite(directNumber) ? directNumber : Number.isFinite(nestedNumber) ? nestedNumber : -1;
    return { code: Number.isFinite(code) ? code : -1, message: e && e.message ? e.message : String(e) };
  }

  _error(e) {
    this._notify('web3-error', this._errorInfo(e));
  }

  _setDisconnected(value) {
    try {
      if (value) localStorage.setItem('web3-connect:disconnected', '1');
      else localStorage.removeItem('web3-connect:disconnected');
    } catch (e) { /* 存储不可用时仍保持当前页面行为。 */ }
    this._broadcastingDisconnectState = true;
    try {
      window.dispatchEvent(new CustomEvent('web3-connect:disconnect-state', {
        detail: { disconnected: value }
      }));
    } finally {
      this._broadcastingDisconnectState = false;
    }
  }

  _handleDisconnectState(disconnected) {
    if (disconnected && this.account) this._applyAccounts([]);
  }

  _ensureConnectionGeneration(generation) {
    if (generation !== this._connectionGeneration) throw this._disconnectedWhileConnecting();
  }

  _disconnectedWhileConnecting() {
    const error = new Error('Disconnected while connecting');
    error.code = 4100;
    return error;
  }

  _isDisconnected() {
    try { return localStorage.getItem('web3-connect:disconnected') === '1'; }
    catch (e) { return false; }
  }

  _attachProvider(provider) {
    // 「是否有钱包」只看 provider 是否存在：只实现了 request() 而没有 on() 的 provider 仍可连接。
    this._setProviderAvailable(!!provider || this.wallets.length > 0);
    if (!provider || typeof provider.on !== 'function' || typeof provider.removeListener !== 'function') {
      if (this._listeningProvider) this._detachProvider();
      return !!provider;
    }
    if (this._listeningProvider !== provider) {
      if (this._listeningProvider) this._detachProvider();
      provider.on('accountsChanged', this._onAccountsChanged);
      provider.on('chainChanged', this._onChainChanged);
      provider.on('disconnect', this._onDisconnect);
      this._listeningProvider = provider;
    }
    return true;
  }

  /** 只在可用性变化（或挂载后首次）时派发 web3-provider-detected，避免每次 connect() 都向服务端发事件。 */
  _setProviderAvailable(available) {
    if (this._providerAnnounced && this.providerAvailable === available) {
      return;
    }
    this._providerAnnounced = true;
    this.providerAvailable = available;
    this._notify('web3-provider-detected', { available });
  }

  _detachProvider() {
    const provider = this._listeningProvider;
    if (provider) {
      provider.removeListener('accountsChanged', this._onAccountsChanged);
      provider.removeListener('chainChanged', this._onChainChanged);
      provider.removeListener('disconnect', this._onDisconnect);
    }
    this._listeningProvider = null;
  }

  _notify(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
  }
}

if (!customElements.get(Web3Connect.is)) customElements.define(Web3Connect.is, Web3Connect);

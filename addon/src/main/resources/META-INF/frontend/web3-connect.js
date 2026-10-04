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
      _busy: { state: true }
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
    `;
  }

  constructor() {
    super();
    this.account = '';
    this.chainId = '';
    this.connectText = 'Connect Wallet';
    this.disconnectText = 'Disconnect';
    this.hideButton = false;
    this._busy = false;
    this._onAccountsChanged = (accounts) => this._applyAccounts(accounts);
    this._onChainChanged = (chainId) => {
      this.chainId = chainId || '';
      this._notify('web3-chain-changed', { chainId: this.chainId });
    };
    this._onDisconnect = () => {
      this._applyAccounts([]);
    };
  }

  get provider() {
    return typeof window !== 'undefined' ? window.ethereum : undefined;
  }

  connectedCallback() {
    super.connectedCallback();
    const p = this.provider;
    if (p && p.on) {
      p.on('accountsChanged', this._onAccountsChanged);
      p.on('chainChanged', this._onChainChanged);
      p.on('disconnect', this._onDisconnect);
    }
    this._notify('web3-provider-detected', { available: !!p });
  }

  disconnectedCallback() {
    const p = this.provider;
    if (p && p.removeListener) {
      p.removeListener('accountsChanged', this._onAccountsChanged);
      p.removeListener('chainChanged', this._onChainChanged);
      p.removeListener('disconnect', this._onDisconnect);
    }
    super.disconnectedCallback();
  }

  render() {
    if (this.hideButton) {
      return html``;
    }
    return html`
      <button part="button" ?disabled=${this._busy} @click=${this._toggle}>
        ${this.account ? this.disconnectText : this.connectText}
      </button>
      ${this.account
        ? html`<span part="account" class="account">${this._short(this.account)}</span>`
        : ''}
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
  async connect() {
    const p = this._requireProvider();
    this._busy = true;
    try {
      const accounts = await p.request({ method: 'eth_requestAccounts' });
      this.chainId = await p.request({ method: 'eth_chainId' });
      this._applyAccounts(accounts);
      return this.account;
    } catch (e) {
      this._error(e);
      throw e;
    } finally {
      this._busy = false;
    }
  }

  /** Forgets the connected account app-side (EIP-1193 has no real disconnect). */
  disconnect() {
    this._applyAccounts([]);
  }

  /** Silently restores an already-authorized connection, if any. */
  async restore() {
    const p = this.provider;
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
    return p.request({ method: 'eth_getBalance', params: [this.account, 'latest'] });
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
      if (e && e.code === 4902 && addChainParams) {
        await p.request({
          method: 'wallet_addEthereumChain',
          params: [{ ...addChainParams, chainId: chainIdHex }]
        });
      } else {
        this._error(e);
        throw e;
      }
    }
    this.chainId = chainIdHex;
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

  _error(e) {
    this._notify('web3-error', {
      code: e && e.code != null ? e.code : -1,
      message: e && e.message ? e.message : String(e)
    });
  }

  _notify(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
  }
}

customElements.define(Web3Connect.is, Web3Connect);

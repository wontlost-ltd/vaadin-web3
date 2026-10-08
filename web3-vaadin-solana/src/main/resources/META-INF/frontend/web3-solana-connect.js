import { LitElement, html, css } from 'lit';
import { getWallets } from '@wallet-standard/app';
import { renderWalletPicker, walletPickerStyles, showWalletPicker, chooseWallet, closeWalletPicker,
  focusWalletPicker, handlePickerKeydown } from './web3-wallet-picker.js';
import { registerSolanaServerWallet, resolveServerWalletRequest, rejectServerWalletRequest,
  toBase64, fromBase64 } from './web3-solana-server-wallet.js';

const CONNECT = 'standard:connect';
const DISCONNECT = 'standard:disconnect';
const EVENTS = 'standard:events';
const SIGN_IN = 'solana:signIn';
const SIGN_MESSAGE = 'solana:signMessage';

/** Wallet Standard 钱包是否可用于 Solana 登录：支持 Solana 链、连接，以及 signIn 或 signMessage 之一。 */
export function isSolanaWallet(wallet, chain) {
  const chains = wallet?.chains || [];
  const features = wallet?.features || {};
  const supportsChain = chain ? chains.includes(chain) : chains.some((item) => item.startsWith('solana:'));
  return supportsChain && CONNECT in features && (SIGN_IN in features || SIGN_MESSAGE in features);
}

/** 只接受对原始消息字节的 Ed25519 签名；离链消息封装（signedMessageFormat）的字节与挑战不同，明确拒绝。 */
function requirePlainEd25519(output) {
  if (output.signedMessageFormat || (output.signatureType && output.signatureType !== 'ed25519')) {
    throw Object.assign(new Error('The wallet returned an unsupported signature format'), { code: -32603 });
  }
}

export class Web3SolanaConnect extends LitElement {
  static get is() { return 'web3-solana-connect'; }

  static get properties() {
    return {
      account: { type: String, reflect: true },
      walletName: { type: String, attribute: 'wallet-name' },
      chain: { type: String },
      connectText: { type: String, attribute: 'connect-text' },
      disconnectText: { type: String, attribute: 'disconnect-text' },
      hideButton: { type: Boolean, attribute: 'hide-button' },
      preferredWallet: { type: String, attribute: 'preferred-wallet' },
      pickerTitle: { type: String, attribute: 'picker-title' },
      noWalletText: { type: String, attribute: 'no-wallet-text' },
      closeLabel: { type: String, attribute: 'close-label' },
      serverWallet: { type: String, attribute: 'server-wallet' },
      developmentWalletWarning: { type: String, attribute: 'development-wallet-warning' },
      wallets: { type: Array },
      _busy: { state: true },
      _pickerOpen: { state: true }
    };
  }

  static get styles() {
    return css`
      :host { display: inline-block; }
      :host([hidden]) { display: none !important; }
      button {
        font: inherit; cursor: pointer; border: none; padding: 0.5em 1em;
        border-radius: var(--lumo-border-radius-m, 0.5em);
        color: var(--lumo-primary-contrast-color, #fff); background-color: var(--lumo-primary-color, #1676f3);
      }
      button[disabled] { opacity: 0.5; cursor: default; }
      .account { font-family: var(--lumo-font-family, monospace); margin-inline-start: 0.5em; }
      ${walletPickerStyles}
      .wallet-warning { display: block; color: var(--lumo-error-text-color, #a40000); font-weight: 700; }
    `;
  }

  constructor() {
    super();
    this.account = '';
    this.walletName = '';
    this.chain = '';
    this.connectText = 'Connect Solana wallet';
    this.disconnectText = 'Disconnect';
    this.hideButton = false;
    this.preferredWallet = '';
    this.pickerTitle = 'Choose a Solana wallet';
    this.noWalletText = 'No Solana wallet found. Install a wallet that supports Wallet Standard.';
    this.closeLabel = 'Close';
    this.serverWallet = '';
    this.developmentWalletWarning = 'Development wallet — never use with real assets';
    this.wallets = [];
    this._busy = false;
    this._pickerOpen = false;
    this._wallet = null;
    this._walletAccount = null;
    this._offChange = null;
    this._onPickerKeydown = (event) => handlePickerKeydown(this, event);
    this._refreshWallets = () => this._updateWallets();
  }

  connectedCallback() {
    super.connectedCallback();
    const wallets = getWallets();
    this._offRegister = wallets.on('register', this._refreshWallets);
    this._offUnregister = wallets.on('unregister', this._refreshWallets);
    this._syncServerWallet();
    this._updateWallets();
  }

  disconnectedCallback() {
    this._offRegister?.();
    this._offUnregister?.();
    this._unregisterServerWallet?.();
    this._unregisterServerWallet = null;
    this._serverWalletKey = null;
    this._offChange?.();
    this._offChange = null;
    window.removeEventListener('keydown', this._onPickerKeydown);
    super.disconnectedCallback();
  }

  // 在渲染前同步：此处设置 wallets 会并入本次更新，不会在更新完成后再排一次更新
  willUpdate(changed) {
    if (changed.has('serverWallet') && this.isConnected) this._syncServerWallet();
    if (changed.has('chain') || changed.has('developmentWalletWarning')) this._updateWallets();
  }

  updated(changed) {
    if (changed.has('_pickerOpen') && this._pickerOpen) focusWalletPicker(this);
  }

  render() {
    const label = this.account ? this.disconnectText : this.connectText;
    return html`
      ${this.hideButton ? '' : html`<button part="button" ?disabled=${this._busy}
          @click=${() => (this.account ? this.disconnect() : this.connect().catch(() => {}))}>${label}</button>
        ${this.account ? html`<span class="account" part="account">${this._short(this.account)}</span>` : ''}`}
      ${this._pickerOpen ? renderWalletPicker(this.wallets, this.pickerTitle, this.noWalletText, this.closeLabel,
        (name) => chooseWallet(this, name), () => closeWalletPicker(this)) : ''}
    `;
  }

  /** 连接钱包并返回 {address, wallet}；有首选且可用的钱包时直接连接，否则弹出选择器。 */
  async connect() {
    this._busy = true;
    try {
      const preferred = this.preferredWallet && this._findWallet(this.preferredWallet);
      const name = preferred ? this.preferredWallet : await showWalletPicker(this);
      const wallet = this._findWallet(name);
      if (!wallet) throw Object.assign(new Error('Selected Solana wallet is no longer available'), { code: 4100 });
      const { accounts } = await wallet.features[CONNECT].connect();
      const account = this._pickAccount(accounts || wallet.accounts);
      if (!account) throw Object.assign(new Error('The wallet returned no Solana account'), { code: 4100 });
      this._useWallet(wallet, account);
      return { address: account.address, wallet: wallet.name };
    } catch (error) {
      this._error(error);
      throw error;
    } finally {
      this._busy = false;
    }
  }

  async disconnect() {
    const wallet = this._wallet;
    this._clear();
    try { await wallet?.features[DISCONNECT]?.disconnect(); } catch (error) { this._error(error); }
  }

  /**
   * 用 solana:signIn 签名服务端签发的挑战；钱包不支持时退回 solana:signMessage 签名服务端渲染好的消息文本。
   * 返回 base64 的公钥、被签名的字节与签名，由服务端逐字节校验。
   */
  async signIn(inputJson, messageText) {
    try {
      this._requireAccount();
      const input = JSON.parse(inputJson);
      const signIn = this._wallet.features[SIGN_IN];
      if (signIn) {
        const wallet = this._wallet;
        const [output] = await signIn.signIn({ ...input, address: this.account });
        requirePlainEd25519(output);
        // 钱包可能改用另一账户签名：服务端按实际签名者登录，组件状态也随之同步
        if (output.account.address !== this.account) this._useWallet(wallet, output.account);
        return { publicKey: toBase64(output.account.publicKey), signedMessage: toBase64(output.signedMessage),
          signature: toBase64(output.signature) };
      }
      const [output] = await this._wallet.features[SIGN_MESSAGE].signMessage(
        { account: this._walletAccount, message: new TextEncoder().encode(messageText) });
      requirePlainEd25519(output);
      return { publicKey: toBase64(this._walletAccount.publicKey), signedMessage: toBase64(output.signedMessage),
        signature: toBase64(output.signature) };
    } catch (error) {
      this._error(error);
      throw error;
    }
  }

  /** 用 solana:signMessage 签名任意字节（base64 进出）。 */
  async signMessage(messageBase64) {
    try {
      this._requireAccount();
      const feature = this._wallet.features[SIGN_MESSAGE];
      if (!feature) throw Object.assign(new Error('The wallet does not support signing messages'), { code: 4200 });
      const [output] = await feature.signMessage({ account: this._walletAccount, message: fromBase64(messageBase64) });
      return toBase64(output.signature);
    } catch (error) {
      this._error(error);
      throw error;
    }
  }

  _resolveServerWalletRequest(id, json) { resolveServerWalletRequest(this, id, json); }
  _rejectServerWalletRequest(id, code, message) { rejectServerWalletRequest(this, id, code, message); }

  _errorInfo(error) {
    const message = String(error?.message || error || 'Wallet error');
    const code = Number.isInteger(error?.code) ? error.code : -1;
    const userRejected = code === 4001 || /reject|denied|cancel|declined/i.test(message);
    return { code, message, userRejected };
  }

  // 选择器关闭时已报告过同一错误对象，connect() 的 catch 不再重复派发
  _error(error) {
    if (error && typeof error === 'object') {
      if (this._reportedErrors?.has(error)) return;
      (this._reportedErrors ||= new WeakSet()).add(error);
    }
    this._notify('solana-wallet-error', this._errorInfo(error));
  }

  _notify(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
  }

  _requireAccount() {
    if (!this._wallet || !this._walletAccount) {
      throw Object.assign(new Error('Connect a Solana wallet first'), { code: 4100 });
    }
  }

  _findWallet(name) {
    return getWallets().get().find((wallet) => wallet.name === name && isSolanaWallet(wallet, this.chain));
  }

  _pickAccount(accounts) {
    const usable = (accounts || []).filter((account) => !this.chain || (account.chains || []).includes(this.chain));
    return usable[0] || null;
  }

  _useWallet(wallet, account) {
    this._offChange?.();
    this._wallet = wallet;
    this._walletAccount = account;
    this.walletName = wallet.name;
    this.account = account.address;
    this._offChange = wallet.features[EVENTS]?.on('change', ({ accounts }) => {
      if (!accounts) return;
      const next = accounts.find((item) => item.address === this.account) || this._pickAccount(accounts);
      if (!next) { this._clear(); return; }
      if (next.address !== this.account) { this._useWallet(wallet, next); return; }
      this._walletAccount = next;
    }) || null;
    this._notify('solana-account-changed', { account: this.account, wallet: this.walletName });
  }

  _clear() {
    this._offChange?.();
    this._offChange = null;
    const hadAccount = !!this.account;
    this._wallet = null;
    this._walletAccount = null;
    this.account = '';
    this.walletName = '';
    if (hadAccount) this._notify('solana-account-changed', { account: '', wallet: '' });
  }

  _updateWallets() {
    const server = this._serverWalletInfo();
    this.wallets = getWallets().get().filter((wallet) => isSolanaWallet(wallet, this.chain)).map((wallet) => ({
      name: wallet.name,
      rdns: wallet.name,
      icon: wallet.icon,
      warning: server && wallet.name === server.name ? this.developmentWalletWarning : ''
    }));
    if (this._wallet && !getWallets().get().includes(this._wallet)) this._clear();
  }

  _serverWalletInfo() {
    try { return this.serverWallet ? JSON.parse(this.serverWallet) : null; } catch { return null; }
  }

  _syncServerWallet() {
    const info = this._serverWalletInfo();
    const key = info ? `${info.name}|${info.address}|${info.chain}` : null;
    if (key === this._serverWalletKey) return;
    this._unregisterServerWallet?.();
    this._unregisterServerWallet = info ? registerSolanaServerWallet(this, info) : null;
    this._serverWalletKey = key;
  }

  _short(address) {
    return address.length > 12 ? `${address.slice(0, 4)}…${address.slice(-4)}` : address;
  }
}

customElements.define(Web3SolanaConnect.is, Web3SolanaConnect);

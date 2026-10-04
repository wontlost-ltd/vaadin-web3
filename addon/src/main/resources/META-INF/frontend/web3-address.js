import { LitElement, html, css } from 'lit';

/**
 * `<web3-address>` — displays an Ethereum address, abbreviated, with an
 * identicon-style color badge and click-to-copy.
 */
export class Web3Address extends LitElement {
  static get is() {
    return 'web3-address';
  }

  static get properties() {
    return {
      address: { type: String, reflect: true },
      full: { type: Boolean, reflect: true },
      copyable: { type: Boolean, reflect: true },
      _copied: { state: true }
    };
  }

  static get styles() {
    return css`
      :host {
        display: inline-flex;
        align-items: center;
        gap: 0.4em;
        font-family: var(--lumo-font-family, monospace);
      }
      .badge {
        width: 0.9em;
        height: 0.9em;
        border-radius: 50%;
        flex: none;
      }
      .addr {
        cursor: default;
      }
      :host([copyable]) .addr {
        cursor: pointer;
      }
      .copied {
        font-size: 0.8em;
        color: var(--lumo-success-text-color, #2e7d32);
      }
    `;
  }

  constructor() {
    super();
    this.address = '';
    this.full = false;
    this.copyable = false;
    this._copied = false;
  }

  render() {
    if (!this.address) {
      return html``;
    }
    const text = this.full || this.address.length <= 10 || !this.address.startsWith('0x') ? this.address
      : `${this.address.slice(0, 6)}…${this.address.slice(-4)}`;
    return html`
      <span class="badge" part="badge" style="background:${this._color()}"></span>
      <span class="addr" part="address" title=${this.address} @click=${this._copy}>${text}</span>
      ${this._copied ? html`<span class="copied" part="copied">copied</span>` : ''}
    `;
  }

  _color() {
    // Simple deterministic hue from the address.
    let hash = 0;
    const a = this.address.toLowerCase();
    for (let i = 2; i < a.length; i++) {
      hash = (hash * 31 + a.charCodeAt(i)) >>> 0;
    }
    return `hsl(${hash % 360}, 65%, 55%)`;
  }

  async _copy() {
    if (!this.copyable || !navigator.clipboard) {
      return;
    }
    try {
      await navigator.clipboard.writeText(this.address);
      this._copied = true;
      setTimeout(() => (this._copied = false), 1500);
      this.dispatchEvent(new CustomEvent('web3-address-copied', {
        detail: { address: this.address }, bubbles: true, composed: true
      }));
    } catch (e) {
      // Clipboard unavailable (permissions / insecure context) — ignore.
    }
  }
}

if (!customElements.get(Web3Address.is)) customElements.define(Web3Address.is, Web3Address);

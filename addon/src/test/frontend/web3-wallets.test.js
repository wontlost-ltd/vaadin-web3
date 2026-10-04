import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Web3Connect } from '../../main/resources/META-INF/frontend/web3-connect.js';

class Provider {
  constructor(account) { this.account = account; this.listeners = new Map(); this.calls = []; }
  on(name, fn) { const set = this.listeners.get(name) || new Set(); set.add(fn); this.listeners.set(name, set); }
  removeListener(name, fn) { this.listeners.get(name)?.delete(fn); }
  async request({ method }) {
    this.calls.push(method);
    if (method === 'eth_requestAccounts' || method === 'eth_accounts') return [this.account];
    if (method === 'eth_chainId') return '0x1';
    return null;
  }
}

const announce = (uuid, rdns, provider, name = rdns) => window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
  detail: Object.freeze({ info: Object.freeze({ uuid, name, icon: 'data:image/png;base64,AA==', rdns }), provider })
}));
const mount = () => { const element = new Web3Connect(); document.body.append(element); return element; };

beforeEach(() => { document.body.replaceChildren(); localStorage.clear(); delete window.ethereum; });

describe('EIP-6963 wallet discovery', () => {
  it('discovers wallets, de-duplicates announcements and requests providers', async () => {
    const requested = vi.fn();
    window.addEventListener('eip6963:requestProvider', requested);
    const element = mount();
    const changed = vi.fn();
    element.addEventListener('web3-wallets-changed', changed);
    const first = new Provider('0xaaa');
    announce('one', 'io.metamask', first, 'MetaMask');
    announce('two', 'com.coinbase', new Provider('0xbbb'), 'Coinbase');
    announce('one', 'io.metamask', first, 'MetaMask');
    await element.updateComplete;
    expect(element.wallets).toHaveLength(2);
    expect(changed).toHaveBeenCalledTimes(2);
    expect(requested).toHaveBeenCalledTimes(1);
    expect(element.providerAvailable).toBe(true);
    window.removeEventListener('eip6963:requestProvider', requested);
  });

  it('connects the requested wallet and remembers its rdns', async () => {
    const element = mount();
    const other = new Provider('0xbbb');
    const metamask = new Provider('0xaaa');
    announce('other', 'com.other', other);
    announce('mm', 'io.metamask', metamask, 'MetaMask');
    await element.connect('io.metamask');
    expect(metamask.calls).toContain('eth_requestAccounts');
    expect(other.calls).not.toContain('eth_requestAccounts');
    expect(localStorage.getItem('web3-connect:wallet')).toBe('io.metamask');
  });

  it('shows the picker for multiple wallets and connects the clicked choice', async () => {
    const element = mount();
    const first = new Provider('0xaaa');
    const second = new Provider('0xbbb');
    announce('one', 'io.one', first, 'One');
    announce('two', 'io.two', second, 'Two');
    const connecting = element.connect();
    await element.updateComplete;
    element.shadowRoot.querySelectorAll('.wallet-option')[1].click();
    await connecting;
    expect(second.calls).toContain('eth_requestAccounts');
    expect(element.selectedWallet).toBe('io.two');
  });

  it('rejects with 4001 and emits web3-error when the picker closes', async () => {
    const element = mount();
    announce('one', 'io.one', new Provider('0xaaa'));
    announce('two', 'io.two', new Provider('0xbbb'));
    const errorEvent = vi.fn();
    element.addEventListener('web3-error', errorEvent);
    const connecting = element.connect();
    await element.updateComplete;
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await expect(connecting).rejects.toMatchObject({ code: 4001, message: 'User closed the wallet picker' });
    expect(errorEvent.mock.calls[0][0].detail).toEqual({ code: 4001, message: 'User closed the wallet picker' });
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).toBeNull();
  });

  it('removes the picker after a background click and wallet selection', async () => {
    const element = mount();
    announce('one', 'io.one', new Provider('0xaaa'));
    announce('two', 'io.two', new Provider('0xbbb'));
    let connecting = element.connect();
    await element.updateComplete;
    element.shadowRoot.querySelector('.wallet-picker').click();
    await expect(connecting).rejects.toMatchObject({ code: 4001 });
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).toBeNull();

    element.selectedWallet = '';
    connecting = element.connect();
    await element.updateComplete;
    element.shadowRoot.querySelector('.wallet-option').click();
    await connecting;
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).toBeNull();
  });

  it('shows the picker and connects a choice when the button is hidden', async () => {
    const element = mount();
    element.hideButton = true;
    const second = new Provider('0xbbb');
    announce('one', 'io.one', new Provider('0xaaa'));
    announce('two', 'io.two', second);
    const connecting = element.connect();
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).not.toBeNull();
    element.shadowRoot.querySelectorAll('.wallet-option')[1].click();
    await connecting;
    expect(element.account).toBe('0xbbb');
  });

  it('shows the picker when a remembered wallet is not discovered', async () => {
    localStorage.setItem('web3-connect:wallet', 'io.missing');
    const element = mount();
    announce('one', 'io.one', new Provider('0xaaa'));
    announce('two', 'io.two', new Provider('0xbbb'));
    const connecting = element.connect();
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).not.toBeNull();
    element.shadowRoot.querySelector('.wallet-option').click();
    await connecting;
    expect(element.selectedWallet).toBe('io.one');
  });

  it('shows the picker when the preferred wallet is not discovered', async () => {
    const element = mount();
    element.preferredWallet = 'io.missing';
    announce('one', 'io.one', new Provider('0xaaa'));
    announce('two', 'io.two', new Provider('0xbbb'));
    const connecting = element.connect();
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.wallet-picker')).not.toBeNull();
    element.shadowRoot.querySelectorAll('.wallet-option')[1].click();
    await connecting;
    expect(element.selectedWallet).toBe('io.two');
  });

  it('renders wallet icons only for data image URIs', async () => {
    const element = mount();
    announce('valid', 'io.valid', new Provider('0xaaa'));
    window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
      detail: Object.freeze({ info: Object.freeze({ uuid: 'invalid', name: 'Invalid', icon: 'https://example.com/icon.png', rdns: 'io.invalid' }), provider: new Provider('0xbbb') })
    }));
    const connecting = element.connect();
    await element.updateComplete;
    const options = element.shadowRoot.querySelectorAll('.wallet-option');
    expect(options[0].querySelector('img')?.getAttribute('src')).toMatch(/^data:image\//);
    expect(options[1].querySelector('img')).toBeNull();
    element.shadowRoot.querySelector('.wallet-picker').click();
    await expect(connecting).rejects.toMatchObject({ code: 4001 });
  });

  it('uses a single announced wallet and falls back to window.ethereum', async () => {
    const element = mount();
    const announced = new Provider('0xaaa');
    announce('one', 'io.one', announced);
    await element.connect();
    expect(announced.calls).toContain('eth_requestAccounts');
    document.body.replaceChildren();
    window.ethereum = new Provider('0xbbb');
    const fallback = mount();
    await fallback.connect();
    expect(window.ethereum.calls).toContain('eth_requestAccounts');
  });

  it('removes old provider listeners when switching wallets', async () => {
    const element = mount();
    const first = new Provider('0xaaa');
    const second = new Provider('0xbbb');
    announce('one', 'io.one', first);
    await element.connect('io.one');
    announce('two', 'io.two', second);
    await element.connect('io.two');
    expect(first.listeners.get('accountsChanged').size).toBe(0);
    expect(second.listeners.get('accountsChanged').size).toBe(1);
  });

  it('restores the remembered wallet after a delayed announcement', async () => {
    localStorage.setItem('web3-connect:wallet', 'io.remembered');
    const element = mount();
    const provider = new Provider('0xaaa');
    setTimeout(() => announce('remembered', 'io.remembered', provider), 15);
    await element.restore();
    expect(provider.calls).toContain('eth_accounts');
    expect(element.selectedWallet).toBe('io.remembered');
  });
});

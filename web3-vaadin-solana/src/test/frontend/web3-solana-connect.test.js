import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getWallets } from '@wallet-standard/app';
import { Web3SolanaConnect, isSolanaWallet } from '../../main/resources/META-INF/frontend/web3-solana-connect.js';
import { toBase64, fromBase64, SERVER_WALLET_TIMEOUT_MS } from '../../main/resources/META-INF/frontend/web3-solana-server-wallet.js';

const unregisters = [];
const bytes = (length, fill) => new Uint8Array(length).fill(fill);

function account(address, chains = ['solana:devnet'], fill = 7) {
  return { address, publicKey: bytes(32, fill), chains, features: ['solana:signIn', 'solana:signMessage'] };
}

function fakeWallet({ name = 'Phantom', chains = ['solana:devnet'], accounts = [account('Addr1111')], signIn = true,
  signMessage = true, signAndSend = false, signTransaction = false } = {}) {
  const listeners = new Set();
  const features = {
    'standard:connect': { version: '1.0.0', connect: vi.fn(async () => ({ accounts })) },
    'standard:disconnect': { version: '1.0.0', disconnect: vi.fn(async () => {}) },
    'standard:events': { version: '1.0.0', on: (event, listener) => { listeners.add(listener); return () => listeners.delete(listener); } }
  };
  if (signIn) {
    features['solana:signIn'] = { version: '1.0.0', signIn: vi.fn(async (input) => [{
      account: accounts[0], signedMessage: new TextEncoder().encode(`signed:${input.nonce}:${input.address}`),
      signature: bytes(64, 9) }]) };
  }
  if (signMessage) {
    features['solana:signMessage'] = { version: '1.1.0', signMessage: vi.fn(async ({ message }) => [{
      signedMessage: message, signature: bytes(64, 5) }]) };
  }
  if (signAndSend) {
    features['solana:signAndSendTransaction'] = { version: '1.0.0', signAndSendTransaction: vi.fn(async () => [{
      signature: bytes(64, 6) }]) };
  }
  if (signTransaction) {
    features['solana:signTransaction'] = { version: '1.0.0', signTransaction: vi.fn(async ({ transaction }) => [{
      signedTransaction: Uint8Array.from([...transaction, 1]) }]) };
  }
  const wallet = { version: '1.0.0', name, icon: 'data:image/svg+xml,<svg/>', chains, accounts, features,
    emit: (properties) => listeners.forEach((listener) => listener(properties)) };
  unregisters.push(getWallets().register(wallet));
  return wallet;
}

function mount(properties = {}) {
  const element = new Web3SolanaConnect();
  Object.assign(element, { chain: 'solana:devnet', ...properties });
  element.$server = { solanaServerWalletRequest: vi.fn((id, method, payload) => {
    element.lastRequest = { id, method, payload: JSON.parse(payload) };
  }) };
  document.body.append(element);
  return element;
}

const events = (element, type) => {
  const seen = [];
  element.addEventListener(type, (event) => seen.push(event.detail));
  return seen;
};

beforeEach(() => document.body.replaceChildren());
afterEach(() => { while (unregisters.length) unregisters.pop()(); });

describe('wallet discovery', () => {
  it('lists only Solana wallets that can connect and sign, filtered by chain', async () => {
    fakeWallet({ name: 'Phantom' });
    fakeWallet({ name: 'Mainnet only', chains: ['solana:mainnet'], accounts: [account('M', ['solana:mainnet'])] });
    fakeWallet({ name: 'EVM wallet', chains: ['eip155:1'] });
    fakeWallet({ name: 'Cannot sign', signIn: false, signMessage: false });
    const element = mount();

    expect(element.wallets.map((wallet) => wallet.name)).toEqual(['Phantom']);
    element.chain = '';
    await element.updateComplete;
    expect(element.wallets.map((wallet) => wallet.name)).toEqual(['Phantom', 'Mainnet only']);
  });

  it('adds wallets registered after the element was attached', () => {
    const element = mount();
    expect(element.wallets).toEqual([]);
    fakeWallet({ name: 'Late wallet' });
    expect(element.wallets.map((wallet) => wallet.name)).toEqual(['Late wallet']);
  });

  it('isSolanaWallet requires connect plus signIn or signMessage', () => {
    const base = { chains: ['solana:devnet'], features: { 'standard:connect': {} } };
    expect(isSolanaWallet(base)).toBe(false);
    expect(isSolanaWallet({ ...base, features: { ...base.features, 'solana:signMessage': {} } })).toBe(true);
    expect(isSolanaWallet({ ...base, features: { ...base.features, 'solana:signIn': {} } }, 'solana:mainnet')).toBe(false);
    expect(isSolanaWallet(null)).toBe(false);
  });
});

describe('connect', () => {
  it('connects the preferred wallet directly and announces the account', async () => {
    const wallet = fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    const changes = events(element, 'solana-account-changed');

    await expect(element.connect()).resolves.toEqual({ address: 'Addr1111', wallet: 'Phantom' });

    expect(wallet.features['standard:connect'].connect).toHaveBeenCalledOnce();
    expect(element.account).toBe('Addr1111');
    expect(element.walletName).toBe('Phantom');
    expect(changes).toEqual([{ account: 'Addr1111', wallet: 'Phantom' }]);
  });

  it('opens the picker and connects the chosen wallet', async () => {
    fakeWallet();
    const element = mount();
    const connecting = element.connect();
    await element.updateComplete;
    const option = element.shadowRoot.querySelector('.wallet-option');
    expect(option.textContent).toContain('Phantom');

    option.click();

    await expect(connecting).resolves.toEqual({ address: 'Addr1111', wallet: 'Phantom' });
    expect(element._pickerOpen).toBe(false);
  });

  it('closing the picker rejects as a user rejection and reports it once', async () => {
    fakeWallet();
    const element = mount();
    const errors = events(element, 'solana-wallet-error');
    const connecting = element.connect();
    await element.updateComplete;

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    await expect(connecting).rejects.toMatchObject({ code: 4001 });
    expect(errors).toHaveLength(1);
    expect(errors[0].userRejected).toBe(true);
  });

  it('moves focus into the picker when it opens', async () => {
    fakeWallet();
    const element = mount();
    const connecting = element.connect();
    await element.updateComplete;

    expect(element.shadowRoot.activeElement).toBe(element.shadowRoot.querySelector('.wallet-option'));
    element.shadowRoot.querySelector('.wallet-option').click();
    await connecting;
  });

  it('picks the first account on the configured chain', async () => {
    fakeWallet({ accounts: [account('MainnetAcct', ['solana:mainnet']), account('DevnetAcct')] , chains: ['solana:devnet', 'solana:mainnet'] });
    const element = mount({ preferredWallet: 'Phantom' });
    await expect(element.connect()).resolves.toMatchObject({ address: 'DevnetAcct' });
  });

  it('fails when the wallet returns no usable account', async () => {
    fakeWallet({ accounts: [account('MainnetAcct', ['solana:mainnet'])] });
    const element = mount({ preferredWallet: 'Phantom' });
    const errors = events(element, 'solana-wallet-error');
    await expect(element.connect()).rejects.toMatchObject({ code: 4100 });
    expect(errors).toHaveLength(1);
    expect(element.account).toBe('');
  });

  it('follows account changes and clears when the wallet removes every account', async () => {
    const wallet = fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();
    const changes = events(element, 'solana-account-changed');

    wallet.emit({ accounts: [account('Addr2222')] });
    expect(element.account).toBe('Addr2222');
    wallet.emit({ chains: ['solana:devnet'] });
    expect(element.account).toBe('Addr2222');
    wallet.emit({ accounts: [] });

    expect(element.account).toBe('');
    expect(changes).toEqual([{ account: 'Addr2222', wallet: 'Phantom' }, { account: '', wallet: '' }]);
  });

  it('clears the connection when the wallet is unregistered', async () => {
    fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();

    unregisters.pop()();

    expect(element.account).toBe('');
    expect(element.wallets).toEqual([]);
  });

  it('disconnect forgets the account and calls standard:disconnect', async () => {
    const wallet = fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();

    await element.disconnect();

    expect(element.account).toBe('');
    expect(wallet.features['standard:disconnect'].disconnect).toHaveBeenCalledOnce();
  });
});

describe('signing', () => {
  it('uses solana:signIn with the challenge and the connected address', async () => {
    const wallet = fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();

    const result = await element.signIn(JSON.stringify({ domain: 'app.example.com', nonce: 'n1' }), 'unused text');

    expect(wallet.features['solana:signIn'].signIn).toHaveBeenCalledWith(
      { domain: 'app.example.com', nonce: 'n1', address: 'Addr1111' });
    expect(new TextDecoder().decode(fromBase64(result.signedMessage))).toBe('signed:n1:Addr1111');
    expect(fromBase64(result.publicKey)).toEqual(bytes(32, 7));
    expect(fromBase64(result.signature)).toEqual(bytes(64, 9));
    expect(wallet.features['solana:signMessage'].signMessage).not.toHaveBeenCalled();
  });

  it('follows the account the wallet actually signed with', async () => {
    const other = account('OtherAcct', ['solana:devnet'], 8);
    const wallet = fakeWallet({ accounts: [account('Addr1111'), other] });
    wallet.features['solana:signIn'].signIn.mockResolvedValueOnce([{ account: other,
      signedMessage: new Uint8Array([1]), signature: bytes(64, 9) }]);
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();
    const changes = events(element, 'solana-account-changed');

    const result = await element.signIn('{}', '');

    expect(fromBase64(result.publicKey)).toEqual(bytes(32, 8));
    expect(element.account).toBe('OtherAcct');
    expect(changes).toEqual([{ account: 'OtherAcct', wallet: 'Phantom' }]);
  });

  it('rejects off-chain message envelopes and non-Ed25519 signatures', async () => {
    const wallet = fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();
    const signIn = wallet.features['solana:signIn'].signIn;
    signIn.mockResolvedValueOnce([{ account: wallet.accounts[0], signedMessage: new Uint8Array([1]),
      signature: bytes(64, 9), signedMessageFormat: { kind: 'offchainMessage', messageVersion: 1 } }]);
    await expect(element.signIn('{}', '')).rejects.toMatchObject({ code: -32603 });
    signIn.mockResolvedValueOnce([{ account: wallet.accounts[0], signedMessage: new Uint8Array([1]),
      signature: bytes(64, 9), signatureType: 'secp256k1' }]);
    await expect(element.signIn('{}', '')).rejects.toMatchObject({ code: -32603 });
  });

  it('falls back to solana:signMessage with the server-rendered text', async () => {
    const wallet = fakeWallet({ signIn: false });
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();

    const result = await element.signIn('{"nonce":"n1"}', 'app.example.com wants you to sign in');

    const [input] = wallet.features['solana:signMessage'].signMessage.mock.calls[0];
    expect(input.account.address).toBe('Addr1111');
    expect(new TextDecoder().decode(input.message)).toBe('app.example.com wants you to sign in');
    expect(new TextDecoder().decode(fromBase64(result.signedMessage))).toBe('app.example.com wants you to sign in');
    expect(fromBase64(result.signature)).toEqual(bytes(64, 5));
  });

  it('signs arbitrary bytes and refuses before connecting', async () => {
    fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    const errors = events(element, 'solana-wallet-error');
    await expect(element.signMessage(toBase64(new Uint8Array([1, 2])))).rejects.toMatchObject({ code: 4100 });
    expect(errors).toHaveLength(1);

    await element.connect();
    await expect(element.signMessage(toBase64(new Uint8Array([1, 2])))).resolves.toBe(toBase64(bytes(64, 5)));
  });

  it('reports wallet rejections with userRejected', async () => {
    const wallet = fakeWallet();
    wallet.features['solana:signIn'].signIn.mockRejectedValueOnce(Object.assign(new Error('User rejected the request.'), { code: 4001 }));
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();
    const errors = events(element, 'solana-wallet-error');

    await expect(element.signIn('{}', '')).rejects.toThrow('User rejected');

    expect(errors).toEqual([{ code: 4001, message: 'User rejected the request.', userRejected: true }]);
    expect(element._errorInfo(new Error('Transaction declined')).userRejected).toBe(true);
    expect(element._errorInfo('boom')).toEqual({ code: -1, message: 'boom', userRejected: false });
  });
});

describe('transactions', () => {
  it('signs and sends with solana:signAndSendTransaction on the configured chain', async () => {
    const wallet = fakeWallet({ signAndSend: true, signTransaction: true });
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();

    const result = await element.signAndSendTransaction(toBase64(new Uint8Array([9, 8, 7])));

    const [input] = wallet.features['solana:signAndSendTransaction'].signAndSendTransaction.mock.calls[0];
    expect(input.account.address).toBe('Addr1111');
    expect(input.chain).toBe('solana:devnet');
    expect([...input.transaction]).toEqual([9, 8, 7]);
    expect(result).toEqual({ signature: toBase64(bytes(64, 6)) });
    expect(wallet.features['solana:signTransaction'].signTransaction).not.toHaveBeenCalled();
  });

  it('passes the configured chain, otherwise the account\'s first Solana chain', async () => {
    const multi = account('Multi', ['eip155:1', 'solana:mainnet', 'solana:devnet']);
    const wallet = fakeWallet({ signAndSend: true, chains: ['solana:mainnet', 'solana:devnet'], accounts: [multi] });
    const element = mount({ preferredWallet: 'Phantom' });
    await element.connect();
    await element.signAndSendTransaction(toBase64(new Uint8Array([1])));
    element.chain = '';
    await element.updateComplete;
    await element.signAndSendTransaction(toBase64(new Uint8Array([1])));

    const calls = wallet.features['solana:signAndSendTransaction'].signAndSendTransaction.mock.calls;
    expect(calls[0][0].chain).toBe('solana:devnet');
    expect(calls[1][0].chain).toBe('solana:mainnet');
  });

  it('falls back to solana:signTransaction and returns the signed transaction', async () => {
    fakeWallet({ signTransaction: true });
    const element = mount({ preferredWallet: 'Phantom', chain: '' });
    await element.connect();

    const result = await element.signAndSendTransaction(toBase64(new Uint8Array([9])));

    expect([...fromBase64(result.signedTransaction)]).toEqual([9, 1]);
  });

  it('refuses when the wallet cannot sign transactions or is not connected', async () => {
    fakeWallet();
    const element = mount({ preferredWallet: 'Phantom' });
    const errors = events(element, 'solana-wallet-error');
    await expect(element.signAndSendTransaction(toBase64(new Uint8Array([1])))).rejects.toMatchObject({ code: 4100 });
    await element.connect();
    await expect(element.signAndSendTransaction(toBase64(new Uint8Array([1])))).rejects.toMatchObject({ code: 4200 });
    expect(errors).toHaveLength(2);
  });
});

describe('server wallet', () => {
  const info = { name: 'Solana development wallet', address: 'DevAddr', publicKey: toBase64(bytes(32, 3)),
    chain: 'solana:devnet' };

  it('shows the default development warning and syncs wallets within a single render', async () => {
    const element = mount({ serverWallet: JSON.stringify(info) });
    await element.updateComplete;

    element.serverWallet = JSON.stringify({ ...info, address: 'Other' });
    element.chain = 'solana:devnet';
    element.developmentWalletWarning = 'Development wallet — never use with real assets';
    // updateComplete 解析为 false 表示本次更新又排了一次更新（在 updated() 中同步时 Lit 会告警 change-in-update）
    expect(await element.updateComplete).toBe(true);

    expect(element.wallets).toEqual([expect.objectContaining({ warning: 'Development wallet — never use with real assets' })]);
  });

  it('registers a Wallet Standard wallet that forwards signIn to the server', async () => {
    const element = mount({ serverWallet: JSON.stringify(info), developmentWalletWarning: 'Never use real assets',
      preferredWallet: info.name });
    expect(element.wallets).toEqual([expect.objectContaining({ name: info.name, warning: 'Never use real assets' })]);
    await expect(element.connect()).resolves.toMatchObject({ address: 'DevAddr' });

    const signing = element.signIn('{"nonce":"n1"}', '');
    expect(element.lastRequest.method).toBe('signIn');
    expect(element.lastRequest.payload).toEqual({ nonce: 'n1', address: 'DevAddr' });
    element._resolveServerWalletRequest(element.lastRequest.id,
      JSON.stringify({ signedMessage: toBase64(new Uint8Array([1])), signature: toBase64(bytes(64, 4)) }));

    const result = await signing;
    expect(fromBase64(result.publicKey)).toEqual(bytes(32, 3));
    expect(fromBase64(result.signature)).toEqual(bytes(64, 4));
  });

  it('forwards signMessage and propagates server rejections', async () => {
    const element = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
    await element.connect();

    const signing = element.signMessage(toBase64(new Uint8Array([7])));
    expect(element.lastRequest).toMatchObject({ method: 'signMessage', payload: { message: toBase64(new Uint8Array([7])) } });
    element._rejectServerWalletRequest(element.lastRequest.id, 4200, 'Server wallet method is not allowed');

    await expect(signing).rejects.toMatchObject({ code: 4200, message: 'Server wallet method is not allowed' });
    element._resolveServerWalletRequest('unknown', '{}');
  });

  it('forwards transactions to the server for signing and sending', async () => {
    const element = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
    await element.connect();

    const sending = element.signAndSendTransaction(toBase64(new Uint8Array([4, 2])));
    expect(element.lastRequest).toMatchObject({ method: 'signAndSendTransaction',
      payload: { transaction: toBase64(new Uint8Array([4, 2])) } });
    element._resolveServerWalletRequest(element.lastRequest.id, JSON.stringify({ signature: toBase64(bytes(64, 3)) }));
    await expect(sending).resolves.toEqual({ signature: toBase64(bytes(64, 3)) });

    const wallet = getWallets().get().find((item) => item.name === info.name);
    const signing = wallet.features['solana:signTransaction'].signTransaction({ transaction: new Uint8Array([5]) });
    expect(element.lastRequest).toMatchObject({ method: 'signTransaction', payload: { transaction: toBase64(new Uint8Array([5])) } });
    element._resolveServerWalletRequest(element.lastRequest.id, JSON.stringify({ signedTransaction: toBase64(new Uint8Array([5, 6])) }));
    const [signed] = await signing;
    expect([...signed.signedTransaction]).toEqual([5, 6]);
    expect(wallet.features['solana:signAndSendTransaction'].supportedTransactionVersions).toEqual(['legacy', 0]);
  });

  it('times out a request the server never answers', async () => {
    vi.useFakeTimers();
    try {
      const element = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
      await element.connect();
      const signing = element.signMessage(toBase64(new Uint8Array([7])));
      const assertion = expect(signing).rejects.toMatchObject({ code: -32603, message: 'Server wallet request timed out' });

      await vi.advanceTimersByTimeAsync(SERVER_WALLET_TIMEOUT_MS);

      await assertion;
      expect(element._serverWalletPending.size).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('registers one wallet for several components and forwards through a live one', async () => {
    const first = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
    const second = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
    expect(getWallets().get().filter((wallet) => wallet.name === info.name)).toHaveLength(1);
    expect(first.wallets).toHaveLength(1);

    second.remove();
    expect(getWallets().get().filter((wallet) => wallet.name === info.name)).toHaveLength(1);
    await first.connect();
    const signing = first.signMessage(toBase64(new Uint8Array([7])));
    expect(first.lastRequest.method).toBe('signMessage');
    first._resolveServerWalletRequest(first.lastRequest.id, JSON.stringify({ signature: toBase64(bytes(64, 1)) }));
    await expect(signing).resolves.toBe(toBase64(bytes(64, 1)));

    first.remove();
    expect(getWallets().get().some((wallet) => wallet.name === info.name)).toBe(false);
  });

  it('encodes large messages without overflowing the call stack', () => {
    const large = new Uint8Array(300_000).map((_, index) => index % 251);
    expect(fromBase64(toBase64(large))).toEqual(large);
  });

  it('unregisters and rejects pending requests when removed or replaced', async () => {
    const element = mount({ serverWallet: JSON.stringify(info), preferredWallet: info.name });
    await element.connect();
    const signing = element.signMessage(toBase64(new Uint8Array([7])));

    element.remove();

    await expect(signing).rejects.toMatchObject({ code: 4900 });
    expect(getWallets().get().some((wallet) => wallet.name === info.name)).toBe(false);

    const replaced = mount({ serverWallet: JSON.stringify(info) });
    replaced.serverWallet = '';
    await replaced.updateComplete;
    expect(getWallets().get().some((wallet) => wallet.name === info.name)).toBe(false);
    replaced.serverWallet = 'not json';
    await replaced.updateComplete;
    expect(replaced.wallets).toEqual([]);
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Web3Connect } from '../../main/resources/META-INF/frontend/web3-connect.js';

const wallet = { uuid: 'dev-uuid', name: 'Development wallet', rdns: 'com.example.dev',
  chainId: '0x7a69', accounts: ['0xabc'] };
const announce = (uuid, rdns, provider) => window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
  detail: { info: { uuid, name: rdns, rdns, icon: 'data:image/png;base64,AA==' }, provider }
}));
const mount = () => {
  const element = new Web3Connect();
  element.serverWallet = JSON.stringify(wallet);
  element.developmentWalletWarning = 'Development wallet — never use with real assets';
  element.$server = { serverWalletRequest: vi.fn((id, method, params) => {
    element.requestCall = { id, method, params };
  }) };
  document.body.append(element);
  return element;
};

beforeEach(() => { document.body.replaceChildren(); delete window.ethereum; });

describe('EIP-6963 server wallet', () => {
  it('announces and re-announces while preserving window.ethereum and other wallets', () => {
    const realProvider = { request: vi.fn() };
    window.ethereum = realProvider;
    const element = mount();
    const announced = vi.fn();
    window.addEventListener('eip6963:announceProvider', announced);
    window.dispatchEvent(new Event('eip6963:requestProvider'));
    expect(announced.mock.calls.some(([event]) => event.detail.info.rdns === wallet.rdns)).toBe(true);
    expect(window.ethereum).toBe(realProvider);
    announce('real', 'io.metamask', realProvider);
    expect(element.wallets.map((item) => item.rdns)).toEqual(expect.arrayContaining([wallet.rdns, 'io.metamask']));
    expect(element.wallets.find((item) => item.rdns === wallet.rdns).warning).toContain('never use');
    window.removeEventListener('eip6963:announceProvider', announced);
  });

  it('returns local account and chain data directly', async () => {
    const element = mount();
    await element.updateComplete;
    const provider = element._serverWalletProvider;
    await expect(provider.request({ method: 'eth_accounts' })).resolves.toEqual(wallet.accounts);
    await expect(provider.request({ method: 'eth_requestAccounts' })).resolves.toEqual(wallet.accounts);
    await expect(provider.request({ method: 'eth_chainId' })).resolves.toBe(wallet.chainId);
  });

  it('forwards methods and resolves and rejects through callbacks', async () => {
    const element = mount();
    await element.updateComplete;
    const result = element._serverWalletProvider.request({ method: 'personal_sign', params: ['0x01', '0xabc'] });
    const call = element.requestCall;
    expect(element.$server.serverWalletRequest).toHaveBeenCalledWith(call.id, 'personal_sign', '["0x01","0xabc"]');
    element._resolveServerWalletRequest(call.id, '"0xsig"');
    await expect(result).resolves.toBe('0xsig');

    const rejected = element._serverWalletProvider.request({ method: 'eth_sendTransaction', params: [{ to: '0xabc' }] });
    const next = element.requestCall;
    element._rejectServerWalletRequest(next.id, 4001, 'rejected');
    await expect(rejected).rejects.toMatchObject({ code: 4001, message: 'rejected' });
  });

  it('rejects outstanding requests with 4900 on detach and retains warning in connected state', async () => {
    const element = mount();
    await element.updateComplete;
    element.selectedWallet = wallet.rdns;
    element.account = wallet.accounts[0];
    await element.updateComplete;
    expect(element.shadowRoot.querySelector('.development-wallet-warning')?.textContent).toContain('never use');
    const request = element._serverWalletProvider.request({ method: 'personal_sign', params: [] });
    document.body.replaceChildren();
    await expect(request).rejects.toMatchObject({ code: 4900 });
  });
});

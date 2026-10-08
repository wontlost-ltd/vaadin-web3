import { getWallets } from '@wallet-standard/app';

const ICON = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#8b3d00"/><text x="32" y="28" text-anchor="middle" font-size="22">DEV</text><path d="M12 42h40v12H12z" fill="#ffb000"/><text x="32" y="51" text-anchor="middle" font-size="10">TEST ONLY</text></svg>')}`;

export const toBase64 = (bytes) => btoa(String.fromCharCode(...bytes));
export const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

/**
 * 把服务端开发钱包注册为 Wallet Standard 钱包：私钥只在服务端，签名请求经 $server 转发。
 * 由 web3-solana-connect 在 serverWallet 属性变化时调用；返回注销函数。
 */
export function registerSolanaServerWallet(component, info) {
  component._serverWalletPending ||= new Map();
  const account = Object.freeze({
    address: info.address,
    publicKey: fromBase64(info.publicKey),
    chains: Object.freeze([info.chain]),
    features: Object.freeze(['solana:signIn', 'solana:signMessage'])
  });
  const forward = (method, payload) => new Promise((resolve, reject) => {
    const id = crypto.randomUUID();
    component._serverWalletPending.set(id, { resolve, reject });
    component.$server.solanaServerWalletRequest(id, method, JSON.stringify(payload));
  });
  const wallet = Object.freeze({
    version: '1.0.0',
    name: info.name,
    icon: ICON,
    chains: Object.freeze([info.chain]),
    accounts: Object.freeze([account]),
    features: Object.freeze({
      'standard:connect': { version: '1.0.0', connect: async () => ({ accounts: [account] }) },
      'standard:disconnect': { version: '1.0.0', disconnect: async () => {} },
      'standard:events': { version: '1.0.0', on: () => () => {} },
      'solana:signIn': {
        version: '1.0.0',
        signIn: (...inputs) => Promise.all(inputs.map(async (input) => {
          const result = await forward('signIn', input);
          return { account, signedMessage: fromBase64(result.signedMessage), signature: fromBase64(result.signature),
            signatureType: 'ed25519' };
        }))
      },
      'solana:signMessage': {
        version: '1.1.0',
        signMessage: (...inputs) => Promise.all(inputs.map(async ({ message }) => {
          const result = await forward('signMessage', { message: toBase64(message) });
          return { signedMessage: message, signature: fromBase64(result.signature), signatureType: 'ed25519' };
        }))
      }
    })
  });
  const unregister = getWallets().register(wallet);
  return () => {
    unregister();
    rejectAllServerWalletRequests(component, 4900, 'Server wallet disconnected');
  };
}

export function resolveServerWalletRequest(component, id, json) {
  const pending = component._serverWalletPending?.get(id);
  if (!pending) return;
  component._serverWalletPending.delete(id);
  try { pending.resolve(JSON.parse(json)); } catch (error) { pending.reject(error); }
}

export function rejectServerWalletRequest(component, id, code, message) {
  const pending = component._serverWalletPending?.get(id);
  if (!pending) return;
  component._serverWalletPending.delete(id);
  pending.reject(Object.assign(new Error(message), { code }));
}

export function rejectAllServerWalletRequests(component, code, message) {
  for (const id of [...(component._serverWalletPending?.keys() || [])]) {
    rejectServerWalletRequest(component, id, code, message);
  }
}

export const SERVER_WALLET_ICON = ICON;

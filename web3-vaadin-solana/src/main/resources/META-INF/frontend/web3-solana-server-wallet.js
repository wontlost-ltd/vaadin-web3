import { getWallets } from '@wallet-standard/app';

const ICON = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#8b3d00"/><text x="32" y="28" text-anchor="middle" font-size="22">DEV</text><path d="M12 42h40v12H12z" fill="#ffb000"/><text x="32" y="51" text-anchor="middle" font-size="10">TEST ONLY</text></svg>')}`;
const CHUNK = 0x8000;
/** 服务端未在此时间内答复时拒绝，避免钱包请求永久挂起。 */
export const SERVER_WALLET_TIMEOUT_MS = 60_000;

// 按钱包身份共享注册：同一页面多个组件只注册一次，签名请求经最近挂载且仍在页面上的组件转发
const shared = new Map();

export function toBase64(bytes) {
  let text = '';
  for (let i = 0; i < bytes.length; i += CHUNK) text += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  return btoa(text);
}

export const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

/**
 * 把服务端开发钱包注册为 Wallet Standard 钱包：私钥只在服务端，签名请求经 $server 转发。
 * 返回释放函数；最后一个使用者释放时注销钱包。
 */
export function registerSolanaServerWallet(component, info) {
  const key = `${info.name}|${info.address}|${info.chain}`;
  let entry = shared.get(key);
  if (!entry) {
    entry = { owners: [] };
    entry.unregister = getWallets().register(createWallet(info, entry));
    shared.set(key, entry);
  }
  entry.owners.push(component);
  return () => {
    entry.owners = entry.owners.filter((owner) => owner !== component);
    rejectAllServerWalletRequests(component, 4900, 'Server wallet disconnected');
    if (entry.owners.length === 0 && shared.get(key) === entry) {
      shared.delete(key);
      entry.unregister();
    }
  };
}

function createWallet(info, entry) {
  const account = Object.freeze({
    address: info.address,
    publicKey: fromBase64(info.publicKey),
    chains: Object.freeze([info.chain]),
    features: Object.freeze(['solana:signIn', 'solana:signMessage', 'solana:signTransaction',
      'solana:signAndSendTransaction'])
  });
  const forward = (method, payload) => new Promise((resolve, reject) => {
    const owner = entry.owners.at(-1);
    if (!owner) {
      reject(Object.assign(new Error('Server wallet disconnected'), { code: 4900 }));
      return;
    }
    const id = crypto.randomUUID();
    owner._serverWalletPending ||= new Map();
    const timer = setTimeout(() => rejectServerWalletRequest(owner, id, -32603, 'Server wallet request timed out'),
      SERVER_WALLET_TIMEOUT_MS);
    owner._serverWalletPending.set(id, { resolve, reject, timer });
    owner.$server.solanaServerWalletRequest(id, method, JSON.stringify(payload));
  });
  return Object.freeze({
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
      'solana:signTransaction': {
        version: '1.0.0',
        supportedTransactionVersions: Object.freeze(['legacy', 0]),
        signTransaction: (...inputs) => Promise.all(inputs.map(async ({ transaction }) => {
          const result = await forward('signTransaction', { transaction: toBase64(transaction) });
          return { signedTransaction: fromBase64(result.signedTransaction) };
        }))
      },
      'solana:signAndSendTransaction': {
        version: '1.0.0',
        supportedTransactionVersions: Object.freeze(['legacy', 0]),
        signAndSendTransaction: (...inputs) => Promise.all(inputs.map(async ({ transaction }) => {
          const result = await forward('signAndSendTransaction', { transaction: toBase64(transaction) });
          return { signature: fromBase64(result.signature) };
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
}

function settle(component, id) {
  const pending = component._serverWalletPending?.get(id);
  if (!pending) return null;
  component._serverWalletPending.delete(id);
  clearTimeout(pending.timer);
  return pending;
}

export function resolveServerWalletRequest(component, id, json) {
  const pending = settle(component, id);
  if (!pending) return;
  try { pending.resolve(JSON.parse(json)); } catch (error) { pending.reject(error); }
}

export function rejectServerWalletRequest(component, id, code, message) {
  settle(component, id)?.reject(Object.assign(new Error(message), { code }));
}

export function rejectAllServerWalletRequests(component, code, message) {
  for (const id of [...(component._serverWalletPending?.keys() || [])]) {
    rejectServerWalletRequest(component, id, code, message);
  }
}

export const SERVER_WALLET_ICON = ICON;

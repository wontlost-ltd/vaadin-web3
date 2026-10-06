const ICON = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#8b3d00"/><text x="32" y="28" text-anchor="middle" font-size="22">DEV</text><path d="M12 42h40v12H12z" fill="#ffb000"/><text x="32" y="51" text-anchor="middle" font-size="10">TEST ONLY</text></svg>')}`;

// 转发到服务端钱包的方法：签名类 + 只读节点查询（与 Web3Connect.SERVER_WALLET_METHODS 保持一致）
const FORWARDED_METHODS = ['personal_sign', 'eth_signTypedData_v4', 'eth_sendTransaction',
  'wallet_switchEthereumChain', 'wallet_addEthereumChain',
  'eth_blockNumber', 'eth_call', 'eth_estimateGas', 'eth_feeHistory', 'eth_gasPrice', 'eth_getBalance', 'eth_getBlockByHash', 'eth_getBlockByNumber', 'eth_getCode', 'eth_getLogs', 'eth_getStorageAt', 'eth_getTransactionByHash', 'eth_getTransactionCount', 'eth_getTransactionReceipt', 'eth_maxPriorityFeePerGas', 'net_version'];

export function installServerWallet(component) {
  component._serverWalletPending ||= new Map();
  component._serverWalletProvider = null;
  component._serverWalletAnnouncement = null;
  component._serverWalletRequestProvider = () => component._announceServerWallet();
  component._announceServerWallet = () => {
    const wallet = readWallet(component);
    if (!wallet) return;
    if (!component._serverWalletProvider || component._serverWalletInfo?.uuid !== wallet.uuid) {
      component._serverWalletInfo = wallet;
      component._serverWalletProvider = createProvider(component, wallet);
    }
    const info = Object.freeze({ uuid: wallet.uuid, name: wallet.name, icon: ICON, rdns: wallet.rdns });
    component._serverWalletAnnouncement = Object.freeze({ info, provider: component._serverWalletProvider });
    window.dispatchEvent(new CustomEvent('eip6963:announceProvider', { detail: component._serverWalletAnnouncement }));
  };
  component._resolveServerWalletRequest = (id, json) => {
    const pending = component._serverWalletPending.get(id);
    if (!pending) return;
    component._serverWalletPending.delete(id);
    try { pending.resolve(JSON.parse(json)); } catch (error) { pending.reject(error); }
  };
  component._rejectServerWalletRequest = (id, code, message) => {
    const pending = component._serverWalletPending.get(id);
    if (!pending) return;
    component._serverWalletPending.delete(id);
    pending.reject(Object.assign(new Error(message), { code }));
  };
  component._onServerWalletRequested = () => component._announceServerWallet();
  component._onServerWalletChanged = () => component._announceServerWallet();
  window.addEventListener('eip6963:requestProvider', component._onServerWalletRequested);
  component.addEventListener('server-wallet-changed', component._onServerWalletChanged);
  component._announceServerWallet();
}

export function uninstallServerWallet(component) {
  window.removeEventListener('eip6963:requestProvider', component._onServerWalletRequested);
  component.removeEventListener('server-wallet-changed', component._onServerWalletChanged);
  for (const id of component._serverWalletPending.keys()) {
    component._rejectServerWalletRequest(id, 4900, 'Server wallet disconnected');
  }
}

export function isServerWallet(component, rdns = component.selectedWallet) {
  return !!readWallet(component) && rdns === readWallet(component)?.rdns;
}

function readWallet(component) {
  try { return component.serverWallet ? JSON.parse(component.serverWallet) : null; }
  catch { return null; }
}

function createProvider(component, wallet) {
  return Object.freeze({
    request: ({ method, params = [] }) => {
      if (method === 'eth_accounts' || method === 'eth_requestAccounts') return Promise.resolve(wallet.accounts);
      if (method === 'eth_chainId') return Promise.resolve(wallet.chainId);
      if (!FORWARDED_METHODS.includes(method)) {
        return Promise.reject(Object.assign(new Error(`Unsupported server wallet method: ${method}`), { code: 4200 }));
      }
      const id = crypto.randomUUID();
      return new Promise((resolve, reject) => {
        component._serverWalletPending.set(id, { resolve, reject });
        component.$server.serverWalletRequest(id, method, JSON.stringify(params));
      });
    }
  });
}

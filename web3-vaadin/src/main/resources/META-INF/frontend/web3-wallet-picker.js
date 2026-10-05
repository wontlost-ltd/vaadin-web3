import { css, html } from 'lit';

export const walletPickerStyles = css`
  .wallet-picker { position: fixed; inset: 0; z-index: 10000; display: grid; place-items: center;
    background: var(--lumo-shade-30pct, rgb(0 0 0 / 30%)); }
  .wallet-picker-content { min-width: 18rem; max-width: calc(100vw - 2rem); padding: 1rem;
    border-radius: var(--lumo-border-radius-l, 0.75rem); color: var(--lumo-body-text-color, #222);
    background: var(--lumo-base-color, #fff); box-shadow: var(--lumo-box-shadow-l, 0 8px 24px rgb(0 0 0 / 20%)); }
  .wallet-option { display: flex; align-items: center; gap: 0.75rem; width: 100%; margin-block: 0.5rem;
    color: var(--lumo-body-text-color, #222); background: var(--lumo-contrast-5pct, #f5f5f5); }
  .wallet-option img { width: 2rem; height: 2rem; object-fit: contain; }
`;

export function renderWalletPicker(wallets, title, emptyText, closeLabel, chooseWallet, closePicker) {
  return html`<div class="wallet-picker" part="wallet-picker" @click=${(event) => {
    if (event.target === event.currentTarget) closePicker();
  }}>
    <div class="wallet-picker-content" role="dialog" aria-modal="true" aria-labelledby="web3-wallet-picker-title" tabindex="-1">
      <h2 id="web3-wallet-picker-title">${title}</h2>
      ${wallets.length ? wallets.map((wallet) => html`
        <button class="wallet-option" part="wallet-option" @click=${() => chooseWallet(wallet.rdns)}>
          ${typeof wallet.icon === 'string' && wallet.icon.startsWith('data:image/') ? html`<img src=${wallet.icon} alt="">` : ''}
          <span>${wallet.name}</span>
        </button>`)
        : html`<p>${emptyText}</p>`}
      <button class="wallet-picker-close" aria-label=${closeLabel} @click=${closePicker}>${closeLabel}</button>
    </div>
  </div>`;
}

export function showWalletPicker(component) {
  component._pickerPreviousFocus = component.shadowRoot.activeElement || document.activeElement;
  component._pickerOpen = true;
  window.addEventListener('keydown', component._onPickerKeydown);
  return new Promise((resolve, reject) => { component._pickerResolver = { resolve, reject }; });
}

export function chooseWallet(component, rdns) {
  const resolver = component._pickerResolver;
  if (!resolver) return;
  component.selectedWallet = rdns;
  component._pickerResolver = null;
  component._pickerOpen = false;
  restorePickerFocus(component);
  window.removeEventListener('keydown', component._onPickerKeydown);
  resolver.resolve(rdns);
}

export function closeWalletPicker(component, dispatchError = true) {
  const resolver = component._pickerResolver;
  if (!resolver) return;
  component._pickerResolver = null;
  component._pickerOpen = false;
  restorePickerFocus(component);
  window.removeEventListener('keydown', component._onPickerKeydown);
  const error = Object.assign(new Error('User closed the wallet picker'), { code: 4001 });
  resolver.reject(error);
  if (dispatchError) component._error(error);
}

export function handlePickerKeydown(component, event) {
  if (!component._pickerResolver) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeWalletPicker(component);
    return;
  }
  if (event.key !== 'Tab') return;
  const dialog = component.shadowRoot.querySelector('[role="dialog"]');
  const controls = [...dialog.querySelectorAll('button:not([disabled])')];
  if (!controls.length) { event.preventDefault(); dialog.focus(); return; }
  const first = controls[0];
  const last = controls.at(-1);
  if (event.shiftKey && (component.shadowRoot.activeElement === first || component.shadowRoot.activeElement === dialog)) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && component.shadowRoot.activeElement === last) {
    event.preventDefault(); first.focus();
  }
}

function restorePickerFocus(component) {
  const target = component._pickerPreviousFocus;
  component._pickerPreviousFocus = null;
  if (target?.isConnected) target.focus();
}

export function registerDiscoveredWallet(component, event) {
  const detail = event.detail;
  const info = detail && detail.info;
  if (!info || !info.uuid || !info.rdns || !detail.provider) return;
  const alreadyRegistered = [...component._walletProviders.values()].some((wallet) =>
    wallet.uuid === info.uuid && wallet.rdns === info.rdns);
  if (alreadyRegistered) return;
  component._walletProviders.delete(info.rdns);
  for (const [rdns, wallet] of [...component._walletProviders])
    if (wallet.uuid === info.uuid) component._walletProviders.delete(rdns);
  component._walletProviders.set(info.rdns, { uuid: info.uuid, rdns: info.rdns, provider: detail.provider });
  component._lastWalletInfo.set(info.uuid, info);
  component.wallets = [...component._walletProviders].map(([rdns, wallet]) => {
    const announced = component._lastWalletInfo.get(wallet.uuid) || info;
    return { uuid: wallet.uuid, name: announced.name || rdns, icon: announced.icon || '', rdns };
  });
  component._notify('web3-wallets-changed', { wallets: component.wallets });
  component._attachProvider(component.provider);
  resolveWalletWaiters(component, info.rdns);
}

export function waitForWallet(component, rdns, timeout) {
  if (component._walletProviders.has(rdns)) return Promise.resolve();
  return new Promise((resolve) => {
    const waiter = { rdns, resolve };
    component._walletWaiters ||= [];
    component._walletWaiters.push(waiter);
    setTimeout(() => {
      component._walletWaiters = component._walletWaiters.filter((item) => item !== waiter);
      resolve();
    }, timeout);
  });
}

export function resolveWalletWaiters(component, rdns) {
  for (const waiter of [...(component._walletWaiters || [])]) {
    if (waiter.rdns === rdns) {
      component._walletWaiters = component._walletWaiters.filter((item) => item !== waiter);
      waiter.resolve();
    }
  }
}

export function attachWalletProvider(component, provider) {
  setProviderAvailable(component, !!provider || component.wallets.length > 0);
  if (!provider || typeof provider.on !== 'function' || typeof provider.removeListener !== 'function') {
    if (component._listeningProvider) detachWalletProvider(component);
    return !!provider;
  }
  if (component._listeningProvider !== provider) {
    if (component._listeningProvider) detachWalletProvider(component);
    provider.on('accountsChanged', component._onAccountsChanged);
    provider.on('chainChanged', component._onChainChanged);
    provider.on('disconnect', component._onDisconnect);
    component._listeningProvider = provider;
  }
  return true;
}

export function setProviderAvailable(component, available) {
  if (component._providerAnnounced && component.providerAvailable === available) return;
  component._providerAnnounced = true;
  component.providerAvailable = available;
  component._notify('web3-provider-detected', { available });
}

export function detachWalletProvider(component) {
  const provider = component._listeningProvider;
  if (provider) {
    provider.removeListener('accountsChanged', component._onAccountsChanged);
    provider.removeListener('chainChanged', component._onChainChanged);
    provider.removeListener('disconnect', component._onDisconnect);
  }
  component._listeningProvider = null;
}

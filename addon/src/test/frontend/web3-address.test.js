import { describe, expect, it } from 'vitest';
import { Web3Address } from '../../main/resources/META-INF/frontend/web3-address.js';

const shownText = async (address) => {
  const element = new Web3Address();
  element.address = address;
  document.body.append(element);
  await element.updateComplete;
  return element.shadowRoot.querySelector('.addr')?.textContent;
};

describe('web3-address', () => {
  it('leaves short and non-prefixed values unchanged', async () => {
    expect(await shownText('0x1234567')).toBe('0x1234567');
    expect(await shownText('123456789012345')).toBe('123456789012345');
  });

  it('abbreviates longer 0x-prefixed addresses', async () => {
    expect(await shownText('0x1234567890123456789012345678901234567890')).toBe('0x1234…7890');
  });
});

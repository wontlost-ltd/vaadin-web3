package com.wontlost.web3.onramp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.Tokens;

class FiatOnrampButtonTest {
    @Test void blockedPopupShowsFallbackLink() {
        FiatOnrampButton button = new FiatOnrampButton(provider(), () -> null)
                .setI18n(new FiatOnrampI18n().setContinueTo("Continuer vers {0}"));
        URI uri = URI.create("https://provider.test/one-time");
        button.handlePopupResult(uri, false);
        assertTrue(button.fallbackLink().isVisible());
        assertEquals(uri.toString(), button.fallbackLink().getElement().getAttribute("href"));
        assertEquals("Continuer vers Test Provider", button.fallbackLink().getText());
        assertEquals("noopener", button.fallbackLink().getElement().getAttribute("rel"));
    }

    @Test void failedSessionFiresFailureEventAndNullOrderDoesNothing() {
        AtomicInteger calls = new AtomicInteger();
        FiatOnrampButton failed = new FiatOnrampButton(new TestProvider(true), () -> {
            calls.incrementAndGet();
            return new OnrampOrder(Tokens.usdc(1).orElseThrow(), "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe", null, null, null, null, null, null);
        });
        AtomicInteger failures = new AtomicInteger();
        failed.addOnrampFailedListener(event -> { assertEquals("Test Provider", event.getException().getProvider()); failures.incrementAndGet(); });
        failed.createSession();
        assertEquals(1, calls.get());
        assertEquals(1, failures.get());

        TestProvider unused = new TestProvider(false);
        FiatOnrampButton empty = new FiatOnrampButton(unused, () -> null);
        empty.createSession();
        assertEquals(0, unused.calls);
    }

    private static OnrampProvider provider() { return new TestProvider(false); }
    private static final class TestProvider implements OnrampProvider {
        private static final long serialVersionUID=1L;
        private final boolean fails;
        private int calls;
        TestProvider(boolean fails){this.fails=fails;}
        public String name(){return "Test Provider";}
        public boolean supports(com.wontlost.web3.chain.TokenInfo token){return true;}
        public URI createSession(OnrampOrder order){calls++; if(fails) throw new OnrampException(name(), 503, "offline"); return URI.create("https://provider.test");}
        public boolean isTestEnvironment(){return true;}
    }
}

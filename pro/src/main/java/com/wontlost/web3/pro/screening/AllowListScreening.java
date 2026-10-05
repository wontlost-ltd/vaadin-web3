package com.wontlost.web3.pro.screening;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.wontlost.web3.screening.AddressScreening;

/** Applies an application-local deny list and any configured screenings; any rejection blocks the address. */
public final class AllowListScreening implements AddressScreening {
    private final Set<String> deniedAddresses;
    private final List<AddressScreening> screenings;
    /** Creates a screening chain with an additional local deny list. */
    public AllowListScreening(Set<String> deniedAddresses, AddressScreening... screenings) {
        this.deniedAddresses = deniedAddresses.stream().map(AllowListScreening::normalize).collect(Collectors.toUnmodifiableSet());
        this.screenings = List.of(screenings);
    }
    @Override public ScreeningDecision screen(String address) {
        if (deniedAddresses.contains(normalize(address))) return ScreeningDecision.block("Address is on the application deny list");
        for (AddressScreening screening : screenings) {
            ScreeningDecision result = screening.screen(address);
            if (!result.allowed()) return result;
        }
        return ScreeningDecision.allow();
    }
    private static String normalize(String address) { return address.toLowerCase(Locale.ROOT); }
}

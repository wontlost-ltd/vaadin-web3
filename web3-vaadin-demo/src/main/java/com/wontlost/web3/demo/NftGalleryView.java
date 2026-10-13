package com.wontlost.web3.demo;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.autoconfigure.NftCollections;
import com.wontlost.web3.nft.NftMetadataResolver;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.nft.ui.NftGallery;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;

@Route(value = "nfts", layout = MainLayout.class)
@PageTitle("NFT gallery")
@AnonymousAllowed
public final class NftGalleryView extends DemoPage {
    public NftGalleryView(NftOwnershipSource ownership, NftMetadataResolver metadataResolver,
            NftCollections collections, @Value("${web3.nft.max-page-size:100}") int pageSize) {
        super("NFT gallery", "Browse token collections owned by the connected wallet.", true);
        add(new DemoSection("Your collection", "Connect a wallet to load its NFTs.",
                new NftGallery(ownership, metadataResolver, collections.collections(), pageSize, "/login")));
    }
}

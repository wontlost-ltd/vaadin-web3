package com.wontlost.web3.demo;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.autoconfigure.NftCollections;
import com.wontlost.web3.nft.NftMetadataResolver;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.nft.ui.NftGallery;

@Route(value = "nfts", layout = MainLayout.class)
@PageTitle("NFT gallery")
@AnonymousAllowed
public final class NftGalleryView extends VerticalLayout {
    public NftGalleryView(NftOwnershipSource ownership, NftMetadataResolver metadataResolver,
            NftCollections collections, @Value("${web3.nft.max-page-size:100}") int pageSize) {
        setSizeFull();
        add(new NftGallery(ownership, metadataResolver, collections.collections(), pageSize, "/login"));
    }
}

package com.wontlost.web3.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.Web3Utils;
import com.wontlost.web3.autoconfigure.security.Web3LogoutHandler;
import com.wontlost.web3.autoconfigure.security.Web3Principal;
import com.wontlost.web3.walletconnect.WalletConnect;

/** Provides navigation between the demo views. */
@AnonymousAllowed
public class MainLayout extends AppLayout implements BeforeEnterObserver {
    private final SideNavItem accountItem = new SideNavItem("Account", "/account");
    private final Button signOut = new Button("Sign out");
    private final Span signedIn = new Span();
    private final Button themeToggle = new Button();

    public MainLayout(@Value("${web3.walletconnect.project-id:}") String projectId,
            Web3LogoutHandler web3LogoutHandler, SecurityContextRepository contextRepository) {
        DrawerToggle drawerToggle = new DrawerToggle();
        drawerToggle.setAriaLabel("Toggle navigation");
        Div mark = new Div();
        mark.addClassName("demo-brand__mark");
        Element svg = new Element("svg");
        svg.setAttribute("viewBox", "0 0 32 32");
        svg.setAttribute("aria-hidden", "true");
        svg.setAttribute("width", "26");
        svg.setAttribute("height", "26");
        Element path = new Element("path");
        path.setAttribute("fill", "currentColor");
        path.setAttribute("d", "M16 2 29 9v14l-13 7L3 23V9zm0 4L7 11v10l9 5 9-5V11z m0 5 5 3v5l-5 3-5-3v-5z");
        svg.appendChild(path);
        mark.getElement().appendChild(svg);
        Span brandName = new Span("Vaadin Web3");
        brandName.addClassName("demo-brand__name");
        Span badge = new Span("Demo");
        badge.addClassName("demo-brand__badge");
        HorizontalLayout brand = new HorizontalLayout(mark, brandName, badge);
        brand.addClassName("demo-brand");
        brand.setAlignItems(Alignment.CENTER);
        addToNavbar(drawerToggle, brand);
        signedIn.addClassName("demo-signed-in");
        themeToggle.addClassName("demo-theme-toggle");
        themeToggle.setIcon(VaadinIcon.MOON.create());
        themeToggle.getElement().setAttribute("aria-label", "Toggle dark mode");
        Element themeLabel = new Element("span");
        themeLabel.setAttribute("class", "demo-theme-toggle__label");
        themeLabel.setText("Dark mode");
        themeToggle.getElement().appendChild(themeLabel);
        themeToggle.addClickListener(event -> themeToggle.getElement().executeJs(
                "const r=document.documentElement;const t=(r.getAttribute('theme')||'').split(/\\s+/).filter(Boolean);"
                        + "const dark=t.includes('dark');const n=dark?t.filter(x=>x!=='dark'):[...t,'dark'];"
                        + "if(n.length)r.setAttribute('theme',n.join(' '));else r.removeAttribute('theme');"
                        + "const next=!dark;r.dataset.themeChoice=next?'dark':'light';"
                        + "localStorage.setItem('vaadin-demo-theme',next?'dark':'light');"
                        + "this.querySelector('.demo-theme-toggle__label').textContent=next?'Light mode':'Dark mode';"
                        + "this.setAttribute('aria-pressed',String(next));"));
        Div actions = new Div(signedIn, signOut, themeToggle);
        actions.addClassName("demo-navbar__actions");
        addToNavbar(actions);
        addAttachListener(event -> themeToggle.getElement().executeJs(
                "const r=document.documentElement,s=localStorage.getItem('vaadin-demo-theme');"
                        + "const d=s?s==='dark':matchMedia('(prefers-color-scheme: dark)').matches;"
                        + "const t=(r.getAttribute('theme')||'').split(/\\s+/).filter(x=>x&&x!=='dark');"
                        + "if(d)t.push('dark');r.setAttribute('theme',t.join(' '));"
                        + "if(s)r.dataset.themeChoice=s;"
                        + "this.querySelector('.demo-theme-toggle__label').textContent=d?'Light mode':'Dark mode';"
                        + "this.setAttribute('aria-pressed',String(d));"));

        SideNav navigation = new SideNav();
        SideNavItem wallet = group("Wallet", VaadinIcon.WALLET);
        wallet.addItem(item("Wallet", "/", VaadinIcon.WALLET));
        wallet.addItem(item("Transactions", "/transactions", VaadinIcon.LIST));
        SideNavItem identity = group("Identity", VaadinIcon.USER);
        identity.addItem(item("Sign in", "/login", VaadinIcon.USER));
        identity.addItem(item("Token holders", "/holders", VaadinIcon.USERS));
        identity.addItem(item("NFT gallery", "/nfts", VaadinIcon.PICTURE));
        accountItem.setPrefixComponent(VaadinIcon.USER_CARD.create());
        identity.addItem(accountItem);
        SideNavItem payments = group("Payments", VaadinIcon.CREDIT_CARD);
        payments.addItem(item("Checkout", "/checkout", VaadinIcon.CREDIT_CARD));
        payments.addItem(item("Paid article", "/paid-article", VaadinIcon.FILE_TEXT));
        payments.addItem(item("HTTP x402 API", "/x402-api", VaadinIcon.CLOUD));
        SideNavItem solana = group("Solana", VaadinIcon.CONNECT);
        solana.addItem(item("Solana", "/solana", VaadinIcon.CONNECT));
        navigation.addItem(wallet, identity, payments, solana);
        addToDrawer(navigation);
        signOut.addClickListener(event -> signOut(web3LogoutHandler, contextRepository));
        if (!projectId.isBlank()) {
            WalletConnect walletConnect = new WalletConnect(projectId).setChains(11155111, 84532);
            walletConnect.getStyle().set("display", "none");
            addToNavbar(walletConnect);
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Web3Principal;
        accountItem.setVisible(signedIn);
        signOut.setVisible(signedIn);
        this.signedIn.setVisible(signedIn);
        this.signedIn.setText(signedIn ? Web3Utils.abbreviate(
                ((Web3Principal) authentication.getPrincipal()).address()) : "");
    }

    private SideNavItem item(String label, String path, VaadinIcon icon) {
        SideNavItem item = new SideNavItem(label, path);
        item.setPrefixComponent(icon.create());
        return item;
    }

    private SideNavItem group(String label, VaadinIcon icon) {
        SideNavItem group = new SideNavItem(label);
        group.setPrefixComponent(icon.create());
        return group;
    }

    private void signOut(Web3LogoutHandler logoutHandler, SecurityContextRepository contextRepository) {
        var request = VaadinServletRequest.getCurrent();
        var response = VaadinServletResponse.getCurrent();
        if (request == null || response == null) {
            throw new IllegalStateException("SIWE sign-out requires a current servlet request and response");
        }
        logoutHandler.logout(request.getHttpServletRequest(), response.getHttpServletResponse(),
                SecurityContextHolder.getContext().getAuthentication());
        SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(emptyContext);
        contextRepository.saveContext(emptyContext, request.getHttpServletRequest(), response.getHttpServletResponse());
        getUI().ifPresent(ui -> ui.navigate("login"));
    }
}

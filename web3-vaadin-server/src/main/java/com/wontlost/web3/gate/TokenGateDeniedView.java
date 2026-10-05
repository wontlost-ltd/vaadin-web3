package com.wontlost.web3.gate;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.internal.DefaultErrorHandler;

/** Default view shown when a wallet does not meet a token requirement. */
// 标为默认处理器：使用者为同一异常提供自己的错误视图时，Vaadin 会优先采用使用者的视图
@DefaultErrorHandler
public class TokenGateDeniedView extends Div implements HasErrorParameter<TokenGateDeniedException> {
    @Override
    public int setErrorParameter(BeforeEnterEvent event, ErrorParameter<TokenGateDeniedException> parameter) {
        removeAll();
        add(new H2("Access denied"),
                new Paragraph("Access requires " + parameter.getCustomMessage()),
                new Anchor("/", "Return home"));
        return 403;
    }
}

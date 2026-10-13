package com.wontlost.web3.demo.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/** 展示行内提示或运行环境说明。 */
public class DemoCallout extends Div {
    public DemoCallout(String variant, Component icon, String text) {
        addClassNames("demo-callout", "demo-callout--" + variant);
        HorizontalLayout content = new HorizontalLayout(icon, new Paragraph(text));
        content.addClassName("demo-callout__content");
        content.setPadding(false);
        add(content);
    }
}

package com.wontlost.web3.demo.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;

/** 将相关演示控件和结果归入带标题的卡片。 */
public class DemoSection extends Div {
    public DemoSection(String heading, String description, Component... content) {
        addClassName("demo-section");
        add(new H2(heading));
        if (description != null && !description.isBlank()) add(new Paragraph(description));
        Div body = new Div(content);
        body.addClassName("demo-section__body");
        add(body);
    }
}

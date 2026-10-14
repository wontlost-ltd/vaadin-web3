package com.wontlost.web3.demo.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

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

    /** 带标签的字段与按钮同行时按基线对齐，避免按钮顶到标签高度；窄屏自动换行。 */
    public static HorizontalLayout row(Component... items) {
        HorizontalLayout row = new HorizontalLayout(items);
        row.setAlignItems(Alignment.BASELINE);
        row.setWrap(true);
        return row;
    }
}

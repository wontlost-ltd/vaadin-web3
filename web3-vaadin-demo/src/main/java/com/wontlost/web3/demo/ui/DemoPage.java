package com.wontlost.web3.demo.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** 统一演示页面的标题区和内容容器。 */
public class DemoPage extends VerticalLayout {
    public static DemoPage wide() {
        DemoPage page = new DemoPage();
        page.addClassName("demo-page--wide");
        return page;
    }

    private DemoPage() {
        addClassName("demo-page");
        setPadding(false);
        setSpacing(false);
    }

    public DemoPage(String title, String lead) {
        this();
        Div header = new Div(new H1(title), new Paragraph(lead));
        header.addClassName("demo-page__header");
        add(header);
    }

    public DemoPage(String title, String lead, boolean wide) {
        this(title, lead);
        if (wide) addClassName("demo-page--wide");
    }

    public DemoPage addDevCallout(Component callout) {
        add(callout);
        return this;
    }
}

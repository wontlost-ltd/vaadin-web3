package com.wontlost.web3.demo.ui;

import com.vaadin.flow.component.html.Span;

/** 更新状态时保留原有元素的紧凑提示。 */
public class DemoStatus extends Span {
    public DemoStatus(String variant, String text) {
        setVariant(variant);
        setText(text);
    }

    public void setText(String text) {
        super.setText(text);
        setVisible(text != null && !text.isBlank());
    }

    public void setVariant(String variant) {
        removeClassNames("demo-status--pending", "demo-status--success", "demo-status--error");
        addClassNames("demo-status", "demo-status--" + variant);
    }
}

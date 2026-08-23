package io.qoop.global.model;

public class LogContent extends ir.tamin.framework.logging.common.content.LogContent {
    String message;
    Object content;

    public LogContent(String message, Object content) {
        this.message = message;
        this.content = content;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}

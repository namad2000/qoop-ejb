package io.qoop.util;

import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

public class EvaluationContextData {
    public StandardEvaluationContext context;
    public Object[] args;
    public Object rootObject;
    public Map<String, Object> variables;
}
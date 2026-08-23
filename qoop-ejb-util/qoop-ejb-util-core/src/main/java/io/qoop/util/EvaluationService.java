package io.qoop.util;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.enterprise.context.ApplicationScoped;
import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;

@ApplicationScoped
public class EvaluationService {

    private final ExpressionParser parser = new SpelExpressionParser();

    public EvaluationContextData createEvaluationContext(InvocationContext context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        Object[] args = context.getParameters();
        Method method = context.getMethod();

        evalContext.setRootObject(context.getTarget());

        EvaluationContextData data = new EvaluationContextData();
        data.args = args;
        data.rootObject = context.getTarget();
        data.variables = new HashMap<>();

        if (args != null && args.length > 0) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < args.length; i++) {
                Object argValue = args[i];
                evalContext.setVariable("p" + i, argValue);
                evalContext.setVariable("a" + i, argValue);
                data.variables.put("p" + i, argValue);
                data.variables.put("a" + i, argValue);

                if (parameters != null && i < parameters.length) {
                    String paramName = parameters[i].getName();
                    evalContext.setVariable(paramName, argValue);
                    data.variables.put(paramName, argValue);
                }
            }
            evalContext.setVariable("args", args);
            data.variables.put("args", args);
        }

        data.context = evalContext;
        return data;
    }

    public String evaluateKey(String keyExpr, EvaluationContextData evalData) {
        if (isEmpty(keyExpr)) {
            if (evalData.args == null || evalData.args.length == 0) {
                return "ALL";
            }
            return evalData.args[0] != null ? evalData.args[0].toString() : "NULL";
        }
        Object evaluated = parser.parseExpression(keyExpr).getValue(evalData.context);
        return evaluated != null ? evaluated.toString() : "NULL";
    }

    public boolean evaluateCondition(String condition, StandardEvaluationContext context) {
        if (isEmpty(condition)) return true;
        Boolean result = parser.parseExpression(condition).getValue(context, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public boolean evaluateUnless(String unless, StandardEvaluationContext context, Object result) {
        if (isEmpty(unless)) return false;
        context.setVariable("result", result);
        Boolean res = parser.parseExpression(unless).getValue(context, Boolean.class);
        return Boolean.TRUE.equals(res);
    }

    public ExpressionParser getParser() {
        return parser;
    }

    public boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public String getFirstCacheName(String[] names) {
        if (names == null || names.length == 0) {
            return "default";
        }
        return names[0];
    }
}
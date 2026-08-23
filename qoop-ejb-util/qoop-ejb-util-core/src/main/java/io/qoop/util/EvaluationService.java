package io.qoop.util;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.enterprise.context.ApplicationScoped;
import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EvaluationService {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public EvaluationContextData createEvaluationContext(InvocationContext context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext(context.getTarget());
        Object[] args = context.getParameters();
        Method method = context.getMethod();

        EvaluationContextData data = new EvaluationContextData();
        data.args = args;
        data.rootObject = context.getTarget();
        data.variables = new HashMap<>();

        if (args != null && args.length > 0) {
            Parameter[] parameters = method.getParameters();

            for (int i = 0; i < args.length; i++) {
                Object argValue = args[i];

                String pKey = "p" + i;
                String aKey = "a" + i;

                evalContext.setVariable(pKey, argValue);
                evalContext.setVariable(aKey, argValue);
                data.variables.put(pKey, argValue);
                data.variables.put(aKey, argValue);

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

        try {
            Expression expression = parseAndCacheExpression(keyExpr);
            Object evaluated = expression.getValue(evalData.context);
            return evaluated != null ? evaluated.toString() : "NULL";
        } catch (Exception e) {
            return "NULL";
        }
    }

    public boolean evaluateCondition(String condition, StandardEvaluationContext context) {
        if (isEmpty(condition)) return true;
        try {
            Expression expression = parseAndCacheExpression(condition);
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean evaluateUnless(String unless, StandardEvaluationContext context, Object result) {
        if (isEmpty(unless)) return false;
        try {
            context.setVariable("result", result);
            Expression expression = parseAndCacheExpression(unless);
            Boolean res = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(res);
        } catch (Exception e) {
            return false;
        }
    }

    public Expression parseAndCacheExpression(String expressionText) {
        if (isEmpty(expressionText)) {
            return null;
        }
        return expressionCache.computeIfAbsent(expressionText, parser::parseExpression);
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
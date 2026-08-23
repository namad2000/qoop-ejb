package io.qoop.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheEvaluationService & EvaluationContextData Unit Tests")
class EvaluationServiceTest {

    private EvaluationService evaluationService;

    @Mock
    private InvocationContext invocationContext;

    public static class SampleTarget {
        public void dummyMethod(String param1, Integer param2) {
        }
    }

    @BeforeEach
    void setUp() {
        evaluationService = new EvaluationService();
    }

    @Nested
    @DisplayName("EvaluationContext Creation Tests")
    class CreateEvaluationContextTests {

        @Test
        @DisplayName("Should create context with null parameters array")
        void createEvaluationContext_WithNullParameters_ShouldPopulateVariablesWithNull() throws Exception {
            Object targetObject = new SampleTarget();
            Method method = SampleTarget.class.getMethod("dummyMethod", String.class, Integer.class);

            when(invocationContext.getTarget()).thenReturn(targetObject);
            when(invocationContext.getMethod()).thenReturn(method);
            when(invocationContext.getParameters()).thenReturn(null);

            EvaluationContextData result = evaluationService.createEvaluationContext(invocationContext);

            assertNotNull(result);
            assertSame(targetObject, result.rootObject);
            assertNull(result.args);
            assertNotNull(result.context);
            assertSame(targetObject, result.context.getRootObject().getValue());
        }

        @Test
        @DisplayName("Should create context with multiple parameters and bind p0, a0, and real names")
        void createEvaluationContext_WithMultipleParameters_ShouldPopulateVariables() throws Exception {
            Object targetObject = new SampleTarget();
            Method method = SampleTarget.class.getMethod("dummyMethod", String.class, Integer.class);
            Object[] args = new Object[]{"val1", 100};

            when(invocationContext.getTarget()).thenReturn(targetObject);
            when(invocationContext.getMethod()).thenReturn(method);
            when(invocationContext.getParameters()).thenReturn(args);

            EvaluationContextData result = evaluationService.createEvaluationContext(invocationContext);

            assertSame(args, result.args);
            assertEquals("val1", result.variables.get("p0"));
            assertEquals("val1", result.variables.get("a0"));
            assertEquals(100, result.variables.get("p1"));
            assertEquals(100, result.variables.get("a1"));
        }
    }

    @Nested
    @DisplayName("Evaluate Key Tests")
    class EvaluateKeyTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Should return ALL when key expression is empty and arguments are null or empty")
        void evaluateKey_WithEmptyKeyAndNoArgs_ShouldReturnAll(String keyExpr) {
            EvaluationContextData evalData = new EvaluationContextData();
            evalData.args = null;

            String resultWithNullArgs = evaluationService.evaluateKey(keyExpr, evalData);
            assertEquals("ALL", resultWithNullArgs);

            evalData.args = new Object[0];
            String resultWithEmptyArgs = evaluationService.evaluateKey(keyExpr, evalData);
            assertEquals("ALL", resultWithEmptyArgs);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return first argument or NULL when key expression is empty and arguments exist")
        void evaluateKey_WithEmptyKeyAndArgs_ShouldReturnFirstArgOrNull(String keyExpr) {
            EvaluationContextData evalData = new EvaluationContextData();

            evalData.args = new Object[]{"firstArg"};
            String resultWithValidArg = evaluationService.evaluateKey(keyExpr, evalData);
            assertEquals("firstArg", resultWithValidArg);

            evalData.args = new Object[]{null};
            String resultWithNullArg = evaluationService.evaluateKey(keyExpr, evalData);
            assertEquals("NULL", resultWithNullArg);
        }

        @Test
        @DisplayName("Should evaluate SpEL expression and return value string or NULL")
        void evaluateKey_WithSpELExpression_ShouldEvaluateCorrectly() {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("p0", "user123");
            context.setVariable("p1", null);

            EvaluationContextData evalData = new EvaluationContextData();
            evalData.context = context;

            String resultValue = evaluationService.evaluateKey("#p0", evalData);
            assertEquals("user123", resultValue);

            String resultNullValue = evaluationService.evaluateKey("#p1", evalData);
            assertEquals("NULL", resultNullValue);
        }
    }

    @Nested
    @DisplayName("Evaluate Condition Tests")
    class EvaluateConditionTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should return true for empty condition")
        void evaluateCondition_WithEmptyCondition_ShouldReturnTrue(String condition) {
            StandardEvaluationContext context = new StandardEvaluationContext();
            boolean result = evaluationService.evaluateCondition(condition, context);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should evaluate condition expression accurately")
        void evaluateCondition_WithValidExpression_ShouldReturnBooleanResult() {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("p0", 10);

            boolean trueResult = evaluationService.evaluateCondition("#p0 > 5", context);
            assertTrue(trueResult);

            boolean falseResult = evaluationService.evaluateCondition("#p0 < 5", context);
            assertFalse(falseResult);

            boolean nullExpressionResult = evaluationService.evaluateCondition("#nonExistentVar", context);
            assertFalse(nullExpressionResult);
        }
    }

    @Nested
    @DisplayName("Evaluate Unless Tests")
    class EvaluateUnlessTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should return false for empty unless expression")
        void evaluateUnless_WithEmptyUnless_ShouldReturnFalse(String unless) {
            StandardEvaluationContext context = new StandardEvaluationContext();
            boolean result = evaluationService.evaluateUnless(unless, context, "someResult");
            assertFalse(result);
        }

        @Test
        @DisplayName("Should set result variable and evaluate unless expression accurately")
        void evaluateUnless_WithValidExpression_ShouldEvaluateWithResultVariable() {
            StandardEvaluationContext context = new StandardEvaluationContext();
            Object executionResult = 100;

            boolean trueResult = evaluationService.evaluateUnless("#result > 50", context, executionResult);
            assertTrue(trueResult);
            assertEquals(executionResult, context.lookupVariable("result"));

            boolean falseResult = evaluationService.evaluateUnless("#result < 50", context, executionResult);
            assertFalse(falseResult);

            boolean nullExpressionResult = evaluationService.evaluateUnless("#nonExistentVar", context, executionResult);
            assertFalse(nullExpressionResult);
        }
    }

    @Nested
    @DisplayName("Utility & Helper Methods Tests")
    class UtilityMethodsTests {

        @Test
        @DisplayName("Should return SpEL ExpressionParser instance")
        void getParser_ShouldReturnNonNullParserInstance() {
            ExpressionParser parser = evaluationService.getParser();
            assertNotNull(parser);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t", "\n"})
        @DisplayName("isEmpty should return true for null, empty or whitespace strings")
        void isEmpty_WithEmptyOrWhitespace_ShouldReturnTrue(String input) {
            assertTrue(evaluationService.isEmpty(input));
        }

        @Test
        @DisplayName("isEmpty should return false for non-empty strings")
        void isEmpty_WithValidString_ShouldReturnFalse() {
            assertFalse(evaluationService.isEmpty("valid_string"));
        }

        @Test
        @DisplayName("Should return first cache name or fallback to 'default'")
        void getFirstCacheName_ShouldReturnExpectedCacheName() {
            assertEquals("default", evaluationService.getFirstCacheName(null));
            assertEquals("default", evaluationService.getFirstCacheName(new String[0]));
            assertEquals("primaryCache", evaluationService.getFirstCacheName(new String[]{"primaryCache", "secondaryCache"}));
        }
    }

    @Nested
    @DisplayName("EvaluationContextData DTO Tests")
    class EvaluationContextDataTests {

        @Test
        @DisplayName("Should correctly hold and expose public field data")
        void evaluationContextData_ShouldAccessFieldsCorrectly() {
            EvaluationContextData data = new EvaluationContextData();
            StandardEvaluationContext context = new StandardEvaluationContext();
            Object[] args = new Object[]{"a", "b"};
            Object rootObj = new Object();

            data.context = context;
            data.args = args;
            data.rootObject = rootObj;

            assertSame(context, data.context);
            assertSame(args, data.args);
            assertSame(rootObj, data.rootObject);
            assertNull(data.variables);
        }
    }
}
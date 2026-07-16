package com.AIBI.aop;

import com.AIBI.AgentTool.DataLoadingTool;
import com.AIBI.AgentTool.ChartOutputTool;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallAspectTest {

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    @Test
    void blocksFurtherExecutorToolCallsAfterLimit() throws Throwable {
        ToolCallAspect aspect = new ToolCallAspect();
        setField(aspect, "maxExecutorToolCalls", 1);
        setField(aspect, "maxSynthesizerToolCalls", 1);

        RunContext context = new RunContext("test-run", 1L);
        context.setModelStage("executor");
        RunContextHolder.set(context);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getTarget()).thenReturn(new DataLoadingTool());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("loadData");
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.aroundToolCall(joinPoint));

        String limited = (String) aspect.aroundToolCall(joinPoint);
        assertEquals("limit", JSON.parseObject(limited).getString("error"));
        assertTrue(JSON.parseObject(limited).getString("message").contains("达到上限"));
        verify(joinPoint).proceed();
    }

    @Test
    void allowsChartValidationAfterSynthesizerLimit() throws Throwable {
        ToolCallAspect aspect = new ToolCallAspect();
        setField(aspect, "maxExecutorToolCalls", 1);
        setField(aspect, "maxSynthesizerToolCalls", 1);

        RunContext context = new RunContext("test-run", 1L);
        context.setModelStage("synthesizer");
        RunContextHolder.set(context);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getTarget()).thenReturn(new ChartOutputTool());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("validateChart");
        when(joinPoint.proceed()).thenReturn("valid");

        assertEquals("valid", aspect.aroundToolCall(joinPoint));
        assertEquals("valid", aspect.aroundToolCall(joinPoint));
        verify(joinPoint, org.mockito.Mockito.times(2)).proceed();
    }

    private static void setField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}

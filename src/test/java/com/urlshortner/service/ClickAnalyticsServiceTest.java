package com.urlshortner.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ClickAnalyticsServiceTest {

    @Mock
    RedisCacheService redisCacheService;

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void batchUpdateFailureReQueuesEveryPoppedCode() {
        Set<String> popped = Set.of("aaaa11", "bbbb22", "cccc33");
        when(redisCacheService.popActiveClickCodes(anyLong())).thenReturn(popped);
        when(redisCacheService.drainClickCount(anyString())).thenReturn(5L);
        doThrow(new DataAccessResourceFailureException("mysql down"))
                .when(jdbcTemplate).batchUpdate(anyString(), anyList());

        ServiceMetrics metrics = new ServiceMetrics(new SimpleMeterRegistry());
        ClickAnalyticsService service = new ClickAnalyticsService(
                redisCacheService, jdbcTemplate, metrics, 1000);
        service.flushClicks();

        // Every popped code must be requeued so the next tick can retry the aggregation.
        for (String code : popped) {
            verify(redisCacheService).reQueueClickCode(code);
        }
    }

    @Test
    void emptyActiveSetShortCircuitsWithoutJdbcCall() {
        when(redisCacheService.popActiveClickCodes(anyLong())).thenReturn(Set.of());

        ServiceMetrics metrics = new ServiceMetrics(new SimpleMeterRegistry());
        ClickAnalyticsService service = new ClickAnalyticsService(
                redisCacheService, jdbcTemplate, metrics, 1000);
        service.flushClicks();

        verify(jdbcTemplate, times(0)).batchUpdate(anyString(), any(java.util.List.class));
    }
}

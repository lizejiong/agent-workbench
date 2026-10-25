package com.lzj.rag.backend.ingestion;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class IngestionWorkerPropertiesTest {
    @Test void invalidLeaseAndUnboundedRetryConfigurationFailAtStartup() {
        assertThatThrownBy(()->new IngestionWorkerProperties(true,1,0,3,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofMinutes(5),Duration.ZERO,5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new IngestionWorkerProperties(true,1,0,3,Duration.ofSeconds(30),Duration.ofSeconds(5),Duration.ZERO,Duration.ofMinutes(5),Duration.ZERO,5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

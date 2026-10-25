package com.lzj.rag.backend;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** KB_TEST_JDBC_URL must name a dedicated disposable test database: fixtures delete this service's rows. */
@EnabledIfEnvironmentVariable(named = "KB_TEST_JDBC_URL", matches = ".+")
class LocalPostgresTest extends KnowledgeBaseContract {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("KB_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("KB_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("KB_TEST_DB_PASSWORD"));
    }
}

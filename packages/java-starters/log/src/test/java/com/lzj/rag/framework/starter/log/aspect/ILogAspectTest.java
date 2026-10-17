package com.lzj.rag.framework.starter.log.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lzj.rag.framework.starter.log.annotation.ILog;
import com.lzj.rag.framework.starter.log.config.LogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ILogAspectTest {

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ILogAspect.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void shouldLogArgumentsResultAndElapsedTime() {
        SampleService proxy = createProxy(new LogProperties());

        String result = proxy.query("G1");

        assertThat(result).isEqualTo("ticket-G1");
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("Method call succeeded")
                .contains("operation=查询车票")
                .contains("method=SampleService#query")
                .contains("args=[\"G1\"]")
                .contains("result=\"ticket-G1\"")
                .contains("elapsedMs=");
    }

    @Test
    void shouldRethrowOriginalExceptionAndLogFailure() {
        SampleService target = new SampleService();
        SampleService proxy = createProxy(new LogProperties(), target);

        assertThatThrownBy(() -> proxy.fail("G2"))
                .isSameAs(target.failure);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("Method call failed")
                .contains("operation=预订失败")
                .contains("args=[\"G2\"]")
                .contains("elapsedMs=")
                .contains("exception=java.lang.IllegalStateException: no ticket");
        assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void shouldHideArgumentsAndResultByDefault() {
        SampleService proxy = createProxy(new LogProperties());

        assertThat(proxy.sensitive("secret")).isEqualTo("token");

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("args=" + ILogAspect.DISABLED_CONTENT)
                .contains("result=" + ILogAspect.DISABLED_CONTENT)
                .doesNotContain("secret")
                .doesNotContain("token");
    }

    @Test
    void shouldTruncateLongArgumentsAndResults() {
        LogProperties properties = new LogProperties();
        properties.setMaxContentLength(20);
        SampleService proxy = createProxy(properties);

        proxy.echo("abcdefghijklmnopqrstuvwxyz");

        String message = appender.list.get(0).getFormattedMessage();
        String args = between(message, "args=", " | result=");
        String result = between(message, "result=", " | elapsedMs=");
        assertThat(args).hasSize(20).endsWith("...");
        assertThat(result).hasSize(20).endsWith("...");
    }

    private SampleService createProxy(LogProperties properties) {
        return createProxy(properties, new SampleService());
    }

    private SampleService createProxy(LogProperties properties, SampleService target) {
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ILogAspect(properties));
        return proxyFactory.getProxy();
    }

    private String between(String value, String start, String end) {
        int startIndex = value.indexOf(start) + start.length();
        int endIndex = value.indexOf(end, startIndex);
        return value.substring(startIndex, endIndex);
    }

    static class SampleService {

        private final IllegalStateException failure = new IllegalStateException("no ticket");

        @ILog(value = "查询车票", recordArgs = true, recordResult = true)
        public String query(String trainCode) {
            return "ticket-" + trainCode;
        }

        @ILog(value = "预订失败", recordArgs = true, recordResult = true)
        public String fail(String trainCode) {
            throw failure;
        }

        @ILog("默认日志")
        public String sensitive(String password) {
            return "token";
        }

        @ILog(value = "回显", recordArgs = true, recordResult = true)
        public String echo(String value) {
            return value;
        }
    }
}

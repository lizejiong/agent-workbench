package com.lzj.rag.framework.common.toolkit;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Objects;

/**
 * Spring 环境工具。
 * <p>
 * 调用方显式传入 {@link Environment}，本工具不持有全局 ApplicationContext。
 */
public final class EnvironmentUtil {

    private static final String DEVELOPMENT_PROFILE = "dev";
    private static final String TEST_PROFILE = "test";
    private static final String PRODUCTION_PROFILE = "prod";

    private EnvironmentUtil() {
    }

    /**
     * 判断是否启用 dev Profile。
     *
     * @param environment Spring 环境对象
     * @return 启用时返回 true
     */
    public static boolean isDevelopment(Environment environment) {
        return isActiveProfile(environment, DEVELOPMENT_PROFILE);
    }

    /**
     * 判断是否启用 test Profile。
     *
     * @param environment Spring 环境对象
     * @return 启用时返回 true
     */
    public static boolean isTest(Environment environment) {
        return isActiveProfile(environment, TEST_PROFILE);
    }

    /**
     * 判断是否启用 prod Profile。
     *
     * @param environment Spring 环境对象
     * @return 启用时返回 true
     */
    public static boolean isProduction(Environment environment) {
        return isActiveProfile(environment, PRODUCTION_PROFILE);
    }

    /**
     * 判断指定 Profile 是否处于活动状态。
     *
     * @param environment Spring 环境对象
     * @param profile Profile 名称
     * @return 启用时返回 true
     */
    public static boolean isActiveProfile(Environment environment, String profile) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile must not be blank");
        }
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }
}

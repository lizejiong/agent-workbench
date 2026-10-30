package com.lzj.rag.backend.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 业务账号配置，包含租户、用户及可选运营权限。
 *
 * @param accounts 配置中的业务账号列表。
 */
@Validated
@ConfigurationProperties("kb.security")
public record KnowledgeBaseSecurityProperties(@NotEmpty List<@Valid Account> accounts) {

    /**
     * 业务账号身份及权限配置；字符串输出隐藏密码。
     *
     * @param username 认证用户名。
     * @param password 认证密码；不应写入公开响应或日志。
     * @param tenantId 数据所属租户标识。
     * @param userId 业务用户标识。
     * @param operator 是否具有运营管理权限。
     */
    public record Account(
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") @NotBlank String username,
            @NotBlank String password,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") @NotBlank String tenantId,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") @NotBlank String userId,
            boolean operator) {

        /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Account {}

        /**
         * 初始化当前组件，保存业务依赖及本次使用的配置。
         *
         * @param username 认证用户名。
         * @param password 认证密码；不应写入公开响应或日志。
         * @param tenantId 数据所属租户标识。
         * @param userId 业务用户标识。
         */
        public Account(String username, String password, String tenantId, String userId) {
            this(username, password, tenantId, userId, false);
        }

        /**
         * 返回隐藏敏感凭据的账号描述。
         *
         * @return 当前业务处理结果；失败时通过异常报告。
         */
        @Override
        public String toString() {
            return "Account[username=" + username + ", password=[REDACTED]]";
        }
    }
}

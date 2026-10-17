package com.lzj.rag.framework.starter.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录方法调用日志的业务方法。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ILog {

    /** 业务操作描述；未填写时使用方法标识。 */
    String value() default "";

    /** 是否记录方法入参；默认关闭，由业务显式选择记录。 */
    boolean recordArgs() default false;

    /** 是否记录方法返回值；默认关闭，由业务显式选择记录。 */
    boolean recordResult() default false;
}

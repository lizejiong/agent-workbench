package com.lzj.rag.framework.common.toolkit;

import org.springframework.beans.BeanUtils;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * JavaBean 属性复制工具。
 * <p>
 * 仅复制名称和类型兼容的属性；按类型创建目标对象时，目标类型需要提供无参构造器。
 */
public final class BeanUtil {

    private BeanUtil() {
    }

    /**
     * 将源对象的同名属性复制到已有目标对象。
     *
     * @param source 源对象
     * @param target 目标对象
     * @param <T> 目标对象类型
     * @return 目标对象
     */
    public static <T> T copyProperties(Object source, T target) {
        BeanUtils.copyProperties(
                Objects.requireNonNull(source, "source must not be null"),
                Objects.requireNonNull(target, "target must not be null")
        );
        return target;
    }

    /**
     * 创建目标类型的新对象，并复制源对象的同名属性。
     *
     * @param source 源对象
     * @param targetType 目标对象类型
     * @param <T> 目标对象类型
     * @return 已复制属性的目标对象
     */
    public static <T> T copyProperties(Object source, Class<T> targetType) {
        Objects.requireNonNull(targetType, "target type must not be null");
        return copyProperties(source, BeanUtils.instantiateClass(targetType));
    }

    /**
     * 将源对象集合逐个复制为目标类型集合。
     *
     * @param sources 源对象集合
     * @param targetType 目标对象类型
     * @param <T> 目标对象类型
     * @return 目标对象集合
     */
    public static <T> List<T> copyToList(Collection<?> sources, Class<T> targetType) {
        Objects.requireNonNull(sources, "sources must not be null");
        return sources.stream()
                .map(source -> copyProperties(source, targetType))
                .toList();
    }
}

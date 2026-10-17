package com.lzj.rag.framework.common.enums;

/**
 * 通用 CRUD 操作类型。
 */
public enum OperationTypeEnum implements CodeEnum {

    /** 新增。 */
    CREATE(1),
    /** 修改。 */
    UPDATE(2),
    /** 删除。 */
    DELETE(3),
    /** 查询。 */
    QUERY(4);

    private final Integer code;

    OperationTypeEnum(Integer code) {
        this.code = code;
    }

    @Override
    public Integer code() {
        return code;
    }
}

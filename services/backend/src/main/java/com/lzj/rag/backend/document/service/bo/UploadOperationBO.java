package com.lzj.rag.backend.document.service.bo;

import java.util.*;

/**
 * 上传预留记录，包含文档身份和待写入的对象键。
 *
 * @param id 当前业务对象的唯一标识。
 * @param objectKey 对象存储中的源文件键。
 * @param status 当前业务操作或任务状态。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record UploadOperationBO(UUID id, String objectKey, String status, boolean replayed) {}

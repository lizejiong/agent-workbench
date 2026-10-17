package com.lzj.rag.framework.convention.page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Framework-neutral pagination request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long current = 1L;
    private long size = 10L;
}

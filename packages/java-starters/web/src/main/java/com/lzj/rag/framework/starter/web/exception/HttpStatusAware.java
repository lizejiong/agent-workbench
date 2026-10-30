package com.lzj.rag.framework.starter.web.exception;

/** Optional HTTP status mapping for exceptions in the shared exception hierarchy. */
public interface HttpStatusAware {
    int httpStatus();
}

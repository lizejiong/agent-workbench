package com.lzj.rag.framework.starter.web.config;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default errors for reactive applications; applications may keep their published error contract. */
@ConfigurationProperties("rag.web.reactive")
public class ReactiveWebProperties {
    private String inputCode = BaseErrorCode.CLIENT_ERROR.code();
    private String inputMessage = BaseErrorCode.CLIENT_ERROR.message();
    private String internalCode = BaseErrorCode.SERVICE_ERROR.code();
    private String internalMessage = BaseErrorCode.SERVICE_ERROR.message();

    public String getInputCode() { return inputCode; }
    public void setInputCode(String inputCode) { this.inputCode = inputCode; }
    public String getInputMessage() { return inputMessage; }
    public void setInputMessage(String inputMessage) { this.inputMessage = inputMessage; }
    public String getInternalCode() { return internalCode; }
    public void setInternalCode(String internalCode) { this.internalCode = internalCode; }
    public String getInternalMessage() { return internalMessage; }
    public void setInternalMessage(String internalMessage) { this.internalMessage = internalMessage; }
}

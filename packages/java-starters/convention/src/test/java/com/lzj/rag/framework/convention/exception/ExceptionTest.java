package com.lzj.rag.framework.convention.exception;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void shouldUseDefaultCodesForEachExceptionCategory() {
        ClientException clientException = new ClientException(BaseErrorCode.CLIENT_ERROR);
        ServiceException serviceException = new ServiceException(BaseErrorCode.SERVICE_ERROR);
        RemoteException remoteException = new RemoteException(BaseErrorCode.REMOTE_ERROR);

        assertThat(clientException.getErrorCode()).isEqualTo("A000001");
        assertThat(serviceException.getErrorCode()).isEqualTo("B000001");
        assertThat(remoteException.getErrorCode()).isEqualTo("C000001");
    }

    @Test
    void shouldAllowBusinessModulesToProvideErrorCodes() {
        ClientException exception = new ClientException(TicketErrorCode.TICKET_SOLD_OUT);

        assertThat(exception.getErrorCode()).isEqualTo("A100001");
        assertThat(exception.getErrorMessage()).isEqualTo("车票已售罄");
        assertThat(exception.getMessage()).isEqualTo("车票已售罄");
    }

    @Test
    void shouldPreferExplicitMessageAndKeepCause() {
        IllegalStateException cause = new IllegalStateException("upstream unavailable");
        RemoteException exception = new RemoteException(
                "余票服务暂不可用",
                cause,
                BaseErrorCode.REMOTE_ERROR
        );

        assertThat(exception.getErrorMessage()).isEqualTo("余票服务暂不可用");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    private enum TicketErrorCode implements ErrorCode {
        TICKET_SOLD_OUT("A100001", "车票已售罄");

        private final String code;
        private final String message;

        TicketErrorCode(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String message() {
            return message;
        }
    }
}

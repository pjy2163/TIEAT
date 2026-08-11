package com.tieat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TieatApiApplicationTest {

    @Test
    void entersNonWebQrOperationsModeOnlyWhenTheCommandOptionIsExplicit() {
        assertThat(TieatApiApplication.isQrOperationsInvocation(new String[0])).isFalse();
        assertThat(TieatApiApplication.isQrOperationsInvocation(new String[] {
            "--server.port=8080"
        })).isFalse();
        assertThat(TieatApiApplication.isQrOperationsInvocation(new String[] {
            "--tieat.qr-operations.command=status",
            "--tieat.qr-operations.store-id=9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"
        })).isTrue();
    }

    @Test
    void rejectsQrOperationOptionsWithoutAnExplicitCommand() {
        assertThatThrownBy(() -> TieatApiApplication.isQrOperationsInvocation(new String[] {
            "--tieat.qr-operations.store-id=9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command");
    }
}

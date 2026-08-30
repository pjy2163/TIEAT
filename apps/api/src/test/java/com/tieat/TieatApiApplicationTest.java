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

    @Test
    void entersNonWebModeForCustomerNameAnonymizationAndRejectsAmbiguousInvocations() {
        assertThat(TieatApiApplication.isNonWebCommandInvocation(new String[] {
            "--tieat.customer-name-anonymization.command=anonymize"
        })).isTrue();

        assertThatThrownBy(() -> TieatApiApplication.isNonWebCommandInvocation(new String[] {
            "--tieat.customer-name-anonymization.retention-days=30"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customer-name-anonymization.command");

        assertThatThrownBy(() -> TieatApiApplication.isNonWebCommandInvocation(new String[] {
            "--tieat.qr-operations.command=status",
            "--tieat.customer-name-anonymization.command=anonymize"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be combined");
    }
}

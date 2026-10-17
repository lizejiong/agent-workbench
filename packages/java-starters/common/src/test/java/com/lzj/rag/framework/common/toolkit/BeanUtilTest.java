package com.lzj.rag.framework.common.toolkit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanUtilTest {

    @Test
    void shouldCopyPropertiesToExistingAndNewTarget() {
        Source source = new Source("G1", 2);
        Target existingTarget = new Target();

        assertThat(BeanUtil.copyProperties(source, existingTarget)).isSameAs(existingTarget);
        assertThat(existingTarget.getTrainNumber()).isEqualTo("G1");
        assertThat(existingTarget.getSeatCount()).isEqualTo(2);

        Target newTarget = BeanUtil.copyProperties(source, Target.class);
        assertThat(newTarget.getTrainNumber()).isEqualTo("G1");
        assertThat(newTarget.getSeatCount()).isEqualTo(2);
    }

    @Test
    void shouldCopyCollectionToTargetType() {
        List<Target> targets = BeanUtil.copyToList(
                List.of(new Source("G1", 2), new Source("G2", 1)),
                Target.class
        );

        assertThat(targets)
                .extracting(Target::getTrainNumber)
                .containsExactly("G1", "G2");
    }

    @Test
    void shouldRejectNullCopyArguments() {
        assertThatThrownBy(() -> BeanUtil.copyProperties(null, new Target()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("source must not be null");
        assertThatThrownBy(() -> BeanUtil.copyProperties(new Source("G1", 1), (Target) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("target must not be null");
        assertThatThrownBy(() -> BeanUtil.copyToList(null, Target.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sources must not be null");
    }

    private static final class Source {

        private final String trainNumber;
        private final int seatCount;

        private Source(String trainNumber, int seatCount) {
            this.trainNumber = trainNumber;
            this.seatCount = seatCount;
        }

        public String getTrainNumber() {
            return trainNumber;
        }

        public int getSeatCount() {
            return seatCount;
        }
    }

    private static final class Target {

        private String trainNumber;
        private int seatCount;

        public String getTrainNumber() {
            return trainNumber;
        }

        public void setTrainNumber(String trainNumber) {
            this.trainNumber = trainNumber;
        }

        public int getSeatCount() {
            return seatCount;
        }

        public void setSeatCount(int seatCount) {
            this.seatCount = seatCount;
        }
    }
}

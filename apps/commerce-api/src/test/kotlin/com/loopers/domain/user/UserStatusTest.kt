package com.loopers.domain.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * 회원 상태의 전이 (P-41).
 *
 * `ProductStatus` 와 같은 모양이다 — **되돌릴 수 있는 것과 최종인 것**이 섞여 있고,
 * 그 차이가 상태를 나누는 이유다. 차단은 풀 수 있고 탈퇴는 풀 수 없다.
 */
class UserStatusTest {
    @DisplayName("허용된 전이는,")
    @Nested
    inner class Allowed {
        @DisplayName("갈 수 있다.")
        @ParameterizedTest
        @CsvSource(
            "ACTIVE, DEACTIVATED",
            "ACTIVE, BLOCKED",
            "ACTIVE, WITHDRAWN",
            "DEACTIVATED, ACTIVE",
            "DEACTIVATED, BLOCKED",
            "DEACTIVATED, WITHDRAWN",
            "BLOCKED, ACTIVE",
            "BLOCKED, WITHDRAWN",
        )
        fun canTransition(from: UserStatus, to: UserStatus) {
            assertThat(from.canTransitionTo(to)).isTrue()
        }
    }

    @DisplayName("차단된 계정은,")
    @Nested
    inner class Blocked {
        @DisplayName("비활성화로 갈 수 없다. 제재를 본인이 내려놓는 모양으로 바꿀 수 있으면 제재가 아니다.")
        @Test
        fun cannotBecomeDeactivated() {
            assertThat(UserStatus.BLOCKED.canTransitionTo(UserStatus.DEACTIVATED)).isFalse()
        }
    }

    @DisplayName("탈퇴는 최종 상태라,")
    @Nested
    inner class Withdrawn {
        @DisplayName("어디로도 갈 수 없다. 되살리는 것은 재가입이지 상태 변경이 아니다.")
        @ParameterizedTest
        @EnumSource(UserStatus::class)
        fun cannotTransitionAnywhere(to: UserStatus) {
            assertThat(UserStatus.WITHDRAWN.canTransitionTo(to)).isFalse()
        }
    }

    @DisplayName("같은 상태로의 전이는,")
    @Nested
    inner class SameStatus {
        @DisplayName("허용하지 않는다. 바뀌는 것이 없는 요청을 성공으로 답하면 요청자가 오해한다.")
        @ParameterizedTest
        @EnumSource(UserStatus::class)
        fun isNotAllowed(status: UserStatus) {
            assertThat(status.canTransitionTo(status)).isFalse()
        }
    }

    @DisplayName("서비스를 쓸 수 있는 상태는,")
    @Nested
    inner class Active {
        @DisplayName("ACTIVE 하나뿐이다.")
        @Test
        fun isOnlyActive() {
            assertThat(UserStatus.entries.filter { it.isActive }).containsExactly(UserStatus.ACTIVE)
        }
    }
}

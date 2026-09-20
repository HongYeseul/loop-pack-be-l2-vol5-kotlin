package com.loopers.config

import com.loopers.interfaces.api.support.UserIdArgumentResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import java.time.Clock

/**
 * 1단계 공통 기반이 **실제 애플리케이션에 붙어 있는지** 확인한다 (설계 10절 1단계).
 *
 * `UserIdArgumentResolverTest` 는 standalone MockMvc 라 resolver 의 동작만 본다.
 * 그 resolver 가 진짜 요청 처리에 등록되었는지는 컨텍스트를 띄워야 알 수 있고, 이 테스트가 그 자리다.
 */
@SpringBootTest
class CommonFoundationConfigTest @Autowired constructor(
    private val clock: Clock,
    private val handlerAdapter: RequestMappingHandlerAdapter,
) {
    @DisplayName("application 이 now 를 만들 수 있도록, Clock 빈이 등록된다 (설계 3절).")
    @Test
    fun registersClockBean() {
        assertThat(clock.instant()).isNotNull()
    }

    @DisplayName("고객 API 가 요청자를 파라미터로 받을 수 있도록, UserIdArgumentResolver 가 등록된다 (P-01).")
    @Test
    fun registersUserIdArgumentResolver() {
        // act
        val registered = handlerAdapter.argumentResolvers.orEmpty().filterIsInstance<UserIdArgumentResolver>()

        // assert
        assertThat(registered).hasSize(1)
    }
}

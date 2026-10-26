package com.loopers.config

import com.loopers.support.interfaces.api.UserIdArgumentResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import java.time.Clock

/** 공통 기반이 실제 애플리케이션 컨텍스트에 등록됐는지 확인 (standalone MockMvc 로는 불가) */
@SpringBootTest
class CommonFoundationConfigTest @Autowired constructor(
    private val clock: Clock,
    private val handlerAdapter: RequestMappingHandlerAdapter,
) {
    @DisplayName("application 이 now 를 만들 수 있도록, Clock 빈이 등록된다.")
    @Test
    fun registersClockBean() {
        assertThat(clock.instant()).isNotNull()
    }

    @DisplayName("고객 API 가 요청자를 파라미터로 받을 수 있도록, UserIdArgumentResolver 가 등록된다.")
    @Test
    fun registersUserIdArgumentResolver() {
        // act
        val registered = handlerAdapter.argumentResolvers.orEmpty().filterIsInstance<UserIdArgumentResolver>()

        // assert
        assertThat(registered).hasSize(1)
    }
}
